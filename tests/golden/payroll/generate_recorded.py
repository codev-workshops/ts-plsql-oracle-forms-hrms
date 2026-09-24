#!/usr/bin/env python3
"""Render tests/golden/payroll/<periodId>.json – the *recorded* legacy expectation of
PKG_PAYROLL.calculate_payroll for one seed pay period (GOLDEN-ORACLE MODE = OFF).

The script is a line-by-line transcription of plsql/packages/PKG_PAYROLL.pkb
(calculate_payroll / calculate_employee_pay / calculate_federal_tax / calculate_state_tax /
calculate_fica / calculate_medicare / get_ytd_earnings) evaluated over the PostgreSQL fixtures
in tools/fixtures/pg/*.sql. It is deliberately independent of the Java TaxEngine: it parses the
SQL fixture files directly and re-implements the legacy arithmetic with decimal ROUND HALF_UP.

    python3 tests/golden/payroll/generate_recorded.py 202406

Rows carry the legacy sign convention (earnings +, taxes/deductions −) and are keyed by
(empId, elementId); a legacy `ELSE 0.05` state fallback is pre-declared with
explanation=UNLISTED_STATE_FALLBACK, a legacy failure with a 0.00 ERROR row (LEGACY_ERROR_ROW).
"""
import json
import re
import sys
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "tools" / "fixtures" / "pg"

# PKG_PAYROLL constants
SS_WAGE_BASE = Decimal("168600")
SS_RATE = Decimal("0.062")
MEDICARE_RATE = Decimal("0.0145")
MEDICARE_ADDL_RATE = Decimal("0.009")
MEDICARE_ADDL_THRESHOLD = Decimal("200000")
STD_SINGLE = Decimal("14600")
STD_MARRIED = Decimal("29200")
ALLOWANCE = Decimal("4300")
STATE_RATES = {"CA": "0.0725", "NY": "0.0685", "TX": "0", "FL": "0", "WA": "0",
               "IL": "0.0495", "PA": "0.0307", "OH": "0.04", "NJ": "0.0637", "MA": "0.05"}
STATE_FALLBACK = Decimal("0.05")
PERIODS = {"WEEKLY": 52, "BIWEEKLY": 26, "SEMIMONTHLY": 24, "MONTHLY": 12}

# 2024 ladders as in calculate_federal_tax (upper bound, rate); HEAD_OF_HOUSEHOLD falls through
# to the ELSE branch of the legacy CASE and returns 0.
LADDERS = {
    "SINGLE": [(11600, "0.10"), (47150, "0.12"), (100525, "0.22"), (191950, "0.24"),
               (243725, "0.32"), (609350, "0.35"), (None, "0.37")],
    "MARRIED_JOINT": [(23200, "0.10"), (94300, "0.12"), (201050, "0.22"), (383900, "0.24"),
                      (487450, "0.32"), (731200, "0.35"), (None, "0.37")],
}
LADDERS["MARRIED_SEPARATE"] = LADDERS["SINGLE"]


def r2(v):
    return Decimal(v).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def rows(table, text):
    """Yield dict rows of `insert into <table> (cols) values (...)` statements."""
    for m in re.finditer(r"insert into %s \(([^)]*)\)\s*values \((.*?)\);" % table, text, re.S):
        cols = [c.strip() for c in m.group(1).split(",")]
        vals = re.findall(r"DATE '([^']*)'|'((?:[^']|'')*)'|(NULL)|([^,\s]+)", m.group(2))
        parsed = []
        for d, s, n, raw in vals:
            if d:
                parsed.append(d)
            elif n:
                parsed.append(None)
            elif s or s == "":
                parsed.append(s.replace("''", "'") if s else raw)
            else:
                parsed.append(raw)
        yield dict(zip(cols, parsed))


def load():
    text = "\n".join(p.read_text() for p in sorted(FIXTURES.glob("*.sql")))
    return {t: list(rows(t, text)) for t in
            ["employees", "salary_records", "pay_periods", "payroll_runs", "payroll_details",
             "employee_tax_info", "employee_pay_elements", "pay_elements"]}


def salary_as_of(db, emp_id, as_of):
    best = None
    for s in db["salary_records"]:
        if int(s["emp_id"]) != emp_id or s["effective_date"] > as_of:
            continue
        if s["end_date"] not in (None, "NULL") and s["end_date"] < as_of:
            continue
        if best is None or s["effective_date"] > best["effective_date"]:
            best = s
    return Decimal(best["base_salary"]) if best else Decimal(0)


def ytd_earnings(db, emp_id, year, extra):
    total = Decimal(0)
    periods = {int(p["period_id"]): p for p in db["pay_periods"]}
    runs = {int(r["run_id"]): r for r in db["payroll_runs"]}
    for d in db["payroll_details"] + extra:
        if int(d["emp_id"]) != emp_id or d["element_type"] != "EARNING" or d["status"] != "CALCULATED":
            continue
        period = periods[int(runs[int(d["run_id"])]["period_id"])] if int(d["run_id"]) in runs else extra_period
        if period["period_start_date"][:4] == str(year):
            total += Decimal(d["amount"])
    return total


def federal(gross, status, allowances, addl, periods):
    ladder = LADDERS.get(status)
    if ladder is None:
        return Decimal(0)
    annual = gross * periods
    std = STD_MARRIED if status == "MARRIED_JOINT" else STD_SINGLE
    taxable = annual - std - allowances * ALLOWANCE
    if taxable <= 0:
        return Decimal(0)
    tax, lower = Decimal(0), Decimal(0)
    for upper, rate in ladder:
        if upper is None or taxable <= upper:
            tax += (taxable - lower) * Decimal(rate)
            break
        tax += (Decimal(upper) - lower) * Decimal(rate)
        lower = Decimal(upper)
    return r2(tax / periods) + addl


def fica(gross, ytd):
    if ytd >= SS_WAGE_BASE:
        return Decimal(0)
    return r2(min(gross, SS_WAGE_BASE - ytd) * SS_RATE)


def medicare(gross, ytd):
    tax = r2(gross * MEDICARE_RATE)
    if ytd + gross > MEDICARE_ADDL_THRESHOLD:
        base = gross if ytd >= MEDICARE_ADDL_THRESHOLD else ytd + gross - MEDICARE_ADDL_THRESHOLD
        tax += r2(base * MEDICARE_ADDL_RATE)
    return tax


def main(period_id):
    global extra_period
    db = load()
    period = next(p for p in db["pay_periods"] if int(p["period_id"]) == period_id)
    extra_period = period
    start, end, freq = period["period_start_date"], period["period_end_date"], period["pay_frequency"]
    periods = PERIODS.get(freq, 12)
    year = int(end[:4])
    out = []
    emps = sorted(int(e["emp_id"]) for e in db["employees"]
                  if e["employment_status"] == "ACTIVE" and e["active_flag"] == "Y")
    for emp in emps:
        salary = salary_as_of(db, emp, end)
        if salary == 0:
            out.append(row(emp, 0, "ERROR", Decimal(0), "ERROR", "-20104",
                           "ORA-20104: No active salary record for employee %d" % emp))
            continue
        gross = r2(salary / periods)
        extra = [{"emp_id": emp, "run_id": -1, "element_type": "EARNING", "status": "CALCULATED",
                  "amount": str(gross)}]
        out.append(row(emp, 1, "EARNING", gross))
        ytd = ytd_earnings(db, emp, year, extra)
        tax = next((t for t in db["employee_tax_info"]
                    if int(t["emp_id"]) == emp and int(t["tax_year"]) == year and t["active_flag"] == "Y"), None)
        status = tax["filing_status"] if tax else "SINGLE"
        allowances = Decimal(tax["federal_allowances"]) if tax else Decimal(0)
        addl = Decimal(tax["additional_fed_wh"] or 0) if tax else Decimal(0)
        state = tax["state_code"] if tax else None
        fed = federal(gross, status, allowances, addl, periods)
        if fed > 0:
            out.append(row(emp, 100, "TAX", -fed,
                           explanation="HEAD_OF_HOUSEHOLD_ZERO_FED" if status == "HEAD_OF_HOUSEHOLD" else None))
        elif status == "HEAD_OF_HOUSEHOLD":
            out.append(row(emp, 100, "TAX", Decimal(0), explanation="HEAD_OF_HOUSEHOLD_ZERO_FED"))
        if state is not None:
            rate = STATE_RATES.get(state)
            st = r2(gross * (Decimal(rate) if rate is not None else STATE_FALLBACK))
            if st > 0:
                out.append(row(emp, 101, "TAX", -st,
                               explanation=None if rate is not None else "UNLISTED_STATE_FALLBACK"))
        ss = fica(gross, ytd)
        if ss > 0:
            out.append(row(emp, 102, "TAX", -ss))
        med = medicare(gross, ytd)
        if med > 0:
            out.append(row(emp, 103, "TAX", -med))
        elements = {int(p["element_id"]): p for p in db["pay_elements"]}
        for epe in sorted((e for e in db["employee_pay_elements"] if int(e["emp_id"]) == emp),
                          key=lambda e: int(elements[int(e["element_id"])]["priority_order"])):
            pe = elements[int(epe["element_id"])]
            if epe["active_flag"] != "Y" or pe["element_type"] not in ("DEDUCTION", "BENEFIT"):
                continue
            if epe["effective_date"] > end or (epe.get("end_date") not in (None, "NULL") and epe["end_date"] < start):
                continue
            if epe.get("override_amount") not in (None, "NULL"):
                amt = Decimal(epe["override_amount"])
            elif pe["calculation_type"] == "FLAT":
                amt = Decimal(epe["amount"] if epe.get("amount") not in (None, "NULL") else pe["default_amount"])
            elif pe["calculation_type"] == "PERCENTAGE":
                pct = epe["percentage"] if epe.get("percentage") not in (None, "NULL") else pe["default_percentage"]
                amt = r2(gross * Decimal(pct) / 100)
            else:
                amt = Decimal(epe["amount"] or 0)
            if amt > 0:
                out.append(row(emp, int(epe["element_id"]), pe["element_type"], -amt))
    pack = {
        "periodId": period_id,
        "derivedFrom": "PKG_PAYROLL.calculate_payroll transcribed by tests/golden/payroll/generate_recorded.py "
                       "over tools/fixtures/pg/*.sql (%s, %s, %d employees)" % (period["period_name"], freq, len(emps)),
        "rows": out,
    }
    target = Path(__file__).resolve().parent / ("%d.json" % period_id)
    target.write_text(json.dumps(pack, indent=2) + "\n")
    print("wrote", target, len(out), "rows")
    write_payroll_latest(db, period, out, period_id)


def write_payroll_latest(db, period, details, period_id):
    """Level 3: VW_PAYROLL_LATEST (tests/reconciliation/pg/vw_payroll_latest.sql) once the Java run
    on this period is APPROVED, in the long form of tests/golden/views-baseline.csv. Same
    aggregation as the view: EARNING sum, ABS(TAX) sum, ABS(DEDUCTION|BENEFIT) sum, signed net,
    ERROR rows excluded, ordered by EMP_ID; numbers rendered like Oracle NUMBER (no trailing zeros)."""
    emps = {int(e["emp_id"]): e for e in db["employees"]}
    lines = ["view,row_no,column,value"]
    row_no = 0
    for emp in sorted({d["empId"] for d in details if d["status"] != "ERROR"}):
        mine = [d for d in details if d["empId"] == emp and d["status"] != "ERROR"]
        amt = lambda types, sign: sum((Decimal(d["amount"]) * sign for d in mine if d["elementType"] in types), Decimal(0))
        e = emps[emp]
        row_no += 1
        cells = [("EMP_ID", emp), ("EMP_NUMBER", e["emp_number"]),
                 ("EMP_NAME", "%s %s" % (e["first_name"], e["last_name"])),
                 ("PERIOD_NAME", period["period_name"]),
                 ("GROSS_PAY", num(amt({"EARNING"}, 1))), ("TOTAL_TAXES", num(amt({"TAX"}, -1))),
                 ("TOTAL_DEDUCTIONS", num(amt({"DEDUCTION", "BENEFIT"}, -1))),
                 ("NET_PAY", num(sum((Decimal(d["amount"]) for d in mine), Decimal(0))))]
        for col, val in cells:
            lines.append("VW_PAYROLL_LATEST,%d,%s,%s" % (row_no, col, val))
    target = Path(__file__).resolve().parent / ("vw_payroll_latest-%d-approved.csv" % period_id)
    target.write_text("\n".join(lines) + "\n")
    print("wrote", target, row_no, "rows")


def num(v):
    v = r2(v)
    return "0" if v == 0 else format(v.normalize(), "f")


def row(emp, element, etype, amount, status="CALCULATED", error_code=None, message=None, explanation=None):
    r = {"empId": emp, "elementId": element, "elementType": etype, "amount": str(r2(amount)),
         "status": status, "errorCode": error_code, "explanation": explanation}
    return r


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 202406)
