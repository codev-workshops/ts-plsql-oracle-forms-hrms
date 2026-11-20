#!/usr/bin/env python3
"""Port the Oracle seed scripts in data/seed/ to the PostgreSQL dev seed migration.

Usage: python3 backend/scripts/port_seed.py  (from the repository root)

Reconciles the seed-vs-DDL mismatches recorded in docs/modernization/checkpoint.md (P1):
  LOCATIONS.PHONE            -> phone_number
  SYSTEM_PARAMETERS.DESCRIPTION -> param_description
  JOB_GRADES.GRADE_LEVEL     -> dropped (always equal to GRADE_ID); grade_code = 'G' || lpad(level, 2, '0')
  'Y'/'N' flags              -> true/false (D-01)
  CREATED_BY 'SYSTEM'        -> 'system:seed' (D-04)
Rows are inserted with ON CONFLICT DO NOTHING so the repeatable migration is idempotent, and
every identity is moved past the seeded IDs afterwards (D-02).
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = [ROOT / "data/seed/01_reference_data.sql", ROOT / "data/seed/02_employee_data.sql"]
TARGET = ROOT / "backend/src/main/resources/db/seed/R__dev_seed.sql"

FLAG_COLUMNS = {
    "active_flag", "overtime_eligible", "benefits_enrolled", "taxable_flag", "pretax_flag",
    "employer_paid", "exempt_flag", "prenote_sent", "paid_flag", "accrual_flag",
    "requires_approval", "requires_document", "half_day_flag", "floating_flag", "editable_flag",
}
RENAMES = {("locations", "phone"): "phone_number",
           ("system_parameters", "description"): "param_description"}
PK = {"locations": "location_code"}

INSERT_RE = re.compile(r"INSERT INTO (\w+)\s*\(([^)]*)\)\s*VALUES\s*\((.*)\);\s*$", re.S | re.I)
UPDATE_RE = re.compile(r"UPDATE (\w+) SET (.*);", re.I)


def split_values(text):
    out, cur, depth, quote, i = [], "", 0, False, 0
    while i < len(text):
        ch = text[i]
        if quote:
            cur += ch
            if ch == "'":
                if i + 1 < len(text) and text[i + 1] == "'":
                    cur += "'"
                    i += 1
                else:
                    quote = False
        elif ch == "'":
            quote, cur = True, cur + ch
        elif ch == "(":
            depth, cur = depth + 1, cur + ch
        elif ch == ")":
            depth, cur = depth - 1, cur + ch
        elif ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
        i += 1
    out.append(cur.strip())
    return out


def convert_value(col, val):
    if val.upper() == "SYSDATE":
        return "CURRENT_TIMESTAMP"
    m = re.fullmatch(r"TO_DATE\('(\d{4}-\d{2}-\d{2})',\s*'YYYY-MM-DD'\)", val, re.I)
    if m:
        return f"DATE '{m.group(1)}'"
    if col in FLAG_COLUMNS and val in ("'Y'", "'N'"):
        return "true" if val == "'Y'" else "false"
    if col == "created_by" and val == "'SYSTEM'":
        return "'system:seed'"
    return val


def statements(text):
    buf = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("--") or stripped.upper() in ("SET DEFINE OFF;", "COMMIT;"):
            continue
        code = re.sub(r"\s+--.*$", "", line).rstrip()
        buf.append(code)
        if code.endswith(";"):
            yield " ".join(s.strip() for s in buf)
            buf = []


def port_insert(stmt):
    m = INSERT_RE.match(stmt)
    table = m.group(1).lower()
    cols = [c.strip().lower() for c in m.group(2).split(",")]
    vals = split_values(m.group(3))
    assert len(cols) == len(vals), stmt
    pairs = []
    for col, val in zip(cols, vals):
        col = RENAMES.get((table, col), col)
        if table == "job_grades" and col == "grade_level":
            pairs.append(("grade_code", f"'G{int(val):02d}'"))
            continue
        pairs.append((col, convert_value(col, val)))
    conflict = PK.get(table, cols[0])
    return (f"INSERT INTO hrms.{table} ({', '.join(c for c, _ in pairs)})\n"
            f"VALUES ({', '.join(v for _, v in pairs)})\n"
            f"ON CONFLICT ({conflict}) DO NOTHING;")


def port_update(stmt):
    m = UPDATE_RE.match(stmt)
    return f"UPDATE hrms.{m.group(1).lower()} SET {m.group(2)};"


RESET = """
-- D-02: move every identity past the seeded IDs, never below its START WITH.
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.table_name, c.column_name,
               pg_get_serial_sequence(format('hrms.%I', c.table_name), c.column_name) AS seq,
               s.seqstart
        FROM information_schema.columns c
        JOIN pg_sequence s
          ON s.seqrelid = pg_get_serial_sequence(format('hrms.%I', c.table_name), c.column_name)::regclass
        WHERE c.table_schema = 'hrms' AND c.is_identity = 'YES'
    LOOP
        EXECUTE format(
            'SELECT setval(%L, GREATEST(COALESCE((SELECT max(%I) FROM hrms.%I), 0) + 1, %s), false)',
            r.seq, r.column_name, r.table_name, r.seqstart);
    END LOOP;
END
$$;

-- D-02: EMP-NNNNNN numbers continue above the largest seeded numeric suffix.
SELECT setval('hrms.seq_emp_number',
              GREATEST((SELECT COALESCE(max(substring(emp_number FROM '^EMP-(\\d{6})$')::bigint), 0) + 1
                        FROM hrms.employees), 1000),
              false);
"""


def main():
    out = ["-- ============================================================================",
           "-- DEV/TEST SEED (repeatable). Generated by backend/scripts/port_seed.py from",
           "-- data/seed/01_reference_data.sql and data/seed/02_employee_data.sql. Do not edit by hand.",
           "-- Loaded only with the 'dev' profile or in tests (classpath:db/seed); never in production.",
           "-- ============================================================================", ""]
    for src in SOURCES:
        out.append(f"-- ---- {src.relative_to(ROOT)} ----")
        for stmt in statements(src.read_text()):
            if stmt.upper().startswith("INSERT"):
                out.append(port_insert(stmt))
            elif stmt.upper().startswith("UPDATE"):
                out.append(port_update(stmt))
            else:
                sys.exit(f"unsupported statement: {stmt[:80]}")
        out.append("")
    out.append(RESET.strip())
    TARGET.write_text("\n".join(out) + "\n")
    print(f"wrote {TARGET.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
