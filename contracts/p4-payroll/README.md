# P4 Payroll – contract

Backend and frontend may assume: every route in `openapi.yaml` (routes, DTOs, status
codes, `x-preauthorize` expressions, `ApiError` codes in `error-codes.md`) is frozen;
Payroll is a **pure Java rewrite** – `TaxEngine` (stateless `BigDecimal`, table-driven from
`tax_brackets` + `SYSTEM_PARAMETERS` `TAX.*`) and `PayrollRunService` (Spring Batch, chunked,
restartable) on PostgreSQL, with no PL/SQL, PL/pgSQL, triggers or façade over
`PKG_PAYROLL`; `payroll-module` owns `PAY_PERIODS`, `PAYROLL_RUNS`, `PAYROLL_DETAILS`,
`PAY_ELEMENTS`, `EMPLOYEE_PAY_ELEMENTS`, `TAX_BRACKETS`, `EMPLOYEE_TAX_INFO` and
`payroll_details_shadow`, reads `SALARY_RECORDS` only through the P3 `salary-module`
interface and bank accounts only through the employee-module masked projection; identity
is the JWT (`empId`, `authorities[]` – `PAYROLL:VIEW` reads, `PAYROLL:APPROVE` writes,
`ADMIN:VIEW` shadow), route `{empId}` is always the payslip *subject*; request validation
is `hrms-validation` `dto/payroll/*` exported as module `p4-payroll` of
`frontend/src/generated/validation-schema.json` (guarded by `ValidationSchemaExporterTest`
and `validation-schema.test.ts`); the React payroll pages (`/payroll/**`) render only when
the runtime flag endpoint reports `payroll=NEW`, which the proxy only allows together with
`payroll.engine=JAVA`; calculation is asynchronous (`202` + status polling) and per-employee
failures are `PAYROLL_DETAILS.STATUS='ERROR'` rows, never a failed run; Level 2 expectations
are recorded fixtures (`legacy_source=recorded`) and Level 3 is
`tests/reconciliation/pg/vw_payroll_latest.sql` against `tests/golden/`. **Out of
contract:** `PAID` status and bank-file generation (Phase 5), employer cost
(`TOTAL_EMPLOYER_COST` stays `null`), garnishments/benefit enrolment maintenance, tax-info
and pay-element CRUD routes, multi-currency, the CDC path of `PayrollShadowRunner`
(`legacySource=oracle-cdc`, delivered as code + unit tests only, `untested-live`), and any
Oracle runtime, emulation or `utPLSQL` run. **Legacy behaviours intentionally NOT
reproduced:** hard-coded 2024 federal brackets / standard deductions / allowance / FICA
constants (BUG-02 → `tax_brackets` + `TAX.<year>.*` parameters, seeded so 2024 inputs match
to the cent); the `ELSE 0.05` unknown-state rate (→ `MISSING_TAX_RATE` error row); zero
federal tax for `HEAD_OF_HOUSEHOLD` (a missing `IF` branch → real 2024 HOH ladder, declared
diff `HEAD_OF_HOUSEHOLD_ZERO_FED`); `COMMIT` every 50 employees with partial run state
visible mid-run (→ chunk transactions, summary written once); the `ELEMENT_ID=0` error row
that violated `FK_PD_ELEMENT` (→ sentinel `PAY_ELEMENTS` row 0); YTD placeholders of `0`
in `get_payslip` (BUG-06 → computed from `APPROVED`/`PAID` runs); `UTL_FILE` register into
the undefined `PAYROLL_DIR` (→ streamed CSV with masked bank columns, audited); status-less
`reverse_payroll` and re-calculation of approved runs (→ `RUN_NOT_REVERSIBLE` /
`RUN_NOT_CALCULABLE`); `p_run_type DEFAULT 'REGULAR'` (→ `runType` required).

## Frozen constants (item 4)

| Constant | Value | Where |
|---|---|---|
| `BASE_PAY_ELEMENT_ID` | `1` | `PayrollConstants` (`hrms-validation`) |
| `FED_TAX_ELEMENT_ID` / `STATE_TAX_ELEMENT_ID` / `FICA_ELEMENT_ID` / `MEDICARE_ELEMENT_ID` | `100` / `101` / `102` / `103` | same; the backend's `PayElementStartupValidator` (`ApplicationRunner`) fails startup unless `PAY_ELEMENTS` has exactly these `(ELEMENT_ID, ELEMENT_CODE, ELEMENT_TYPE='TAX', ACTIVE_FLAG='Y')` rows – `FED_TAX`, `STATE_TAX`, `FICA`, `MEDICARE` (`data/seed/01_reference_data.sql`) |
| `ERROR_ELEMENT_ID` | `0` | sentinel `PAY_ELEMENTS` row (`ERROR`, inactive) added by the P4 backend migration |
| Sign convention | earnings `+`; `TAX` / `DEDUCTION` / `BENEFIT` `−` in `PAYROLL_DETAILS.AMOUNT`; aggregates positive; `netPay = SUM(amount)` | `VW_PAYROLL_LATEST` / `tests/reconciliation/pg/vw_payroll_latest.sql` depend on it |
| Failure contract | one `STATUS='ERROR'` row per failed employee, `elementId 0`, `elementType 'ERROR'`, `amount 0.00`, `errorCode`, `errorMessage`; excluded from view, register, totals, YTD | `error-codes.md` §1.1 |
| Pay-period divisor | `WEEKLY 52`, `BIWEEKLY 26`, `SEMIMONTHLY 24`, `MONTHLY 12` | `calculate_employee_pay` |
| Rounding | every stored amount `ROUND(HALF_UP, 2)` at the same points as legacy: gross, each tax, YTD | `TaxEngine` |
| Proxy flags | `payroll=LEGACY\|NEW` (UI + `/api/payroll/**`), `payroll.engine=LEGACY\|JAVA`; defaults `LEGACY`; `NEW` requires `JAVA`; shadow route mounted regardless | `proxy/flags.env`, `proxy/README.md` |

### `tax_brackets` / `TAX.<year>.*` contract (BUG-02)

`tax_brackets(tax_year, filing_status, bracket_min, bracket_max, tax_rate, base_tax,
state_code, active_flag)` – the P4 migration relaxes `CHK_FILING_STATUS` to also allow
`'ALL'` (state rows). Lookup: federal = active rows `state_code IS NULL AND tax_year = :y
AND filing_status = :fs` ordered by `bracket_min` (the ladder must be contiguous from 0);
state = the single active row `state_code = :st AND tax_year = :y AND filing_status = 'ALL'`.
No match → `MISSING_TAX_RATE`. Scalars are `SYSTEM_PARAMETERS` rows
`PARAM_CATEGORY='TAX'`, `PARAM_NAME` = `<year>.STD_DEDUCTION.<filingStatus>`,
`<year>.ALLOWANCE`, `<year>.SS_WAGE_BASE`, `<year>.SS_RATE`, `<year>.MEDICARE_RATE`,
`<year>.MEDICARE_ADDL_RATE`, `<year>.MEDICARE_ADDL_THRESHOLD`. `taxYear` =
`EXTRACT(YEAR FROM PAY_PERIODS.PERIOD_END_DATE)` (legacy parity, also for the
`EMPLOYEE_TAX_INFO` lookup). No active `EMPLOYEE_TAX_INFO` row → defaults `SINGLE`, 0
allowances, `stateCode NULL`, 0 additional withholding (preserved). `stateCode NULL` →
no state tax and no error (preserved); `MISSING_TAX_RATE` fires only for a non-null state
without a `tax_brackets` row.

2024 seed (must reproduce `PKG_PAYROLL` to the cent; Level 1 golden tests of `TaxEngine`):

| Filing status | Ladder upper bounds (rate) |
|---|---|
| `SINGLE`, `MARRIED_SEPARATE` | 11 600 (10 %), 47 150 (12 %), 100 525 (22 %), 191 950 (24 %), 243 725 (32 %), 609 350 (35 %), ∞ (37 %) |
| `MARRIED_JOINT` | 23 200, 94 300, 201 050, 383 900, 487 450, 731 200, ∞ (same rates) |
| `HEAD_OF_HOUSEHOLD` | 16 550, 63 100, 100 500, 191 950, 243 700, 609 350, ∞ – **diverges** from legacy (which produced 0) |

`base_tax` of each row = cumulative tax at `bracket_min`. State rows (flat, `ALL`):
`CA 0.0725`, `NY 0.0685`, `TX 0.0000`, `FL 0.0000`, `WA 0.0000`, `IL 0.0495`,
`PA 0.0307`, `OH 0.0400`, `NJ 0.0637`, `MA 0.0500` – copied from `calculate_state_tax`
(`PKG_PAYROLL.pkb` lines 704–713; this table is the contract, the package is the
reference). Scalars: `STD_DEDUCTION.SINGLE/MARRIED_SEPARATE/HEAD_OF_HOUSEHOLD =
14600.00`, `STD_DEDUCTION.MARRIED_JOINT = 29200.00`, `ALLOWANCE = 4300.00`,
`SS_WAGE_BASE = 168600.00`, `SS_RATE = 0.0620`, `MEDICARE_RATE = 0.0145`,
`MEDICARE_ADDL_RATE = 0.0090`, `MEDICARE_ADDL_THRESHOLD = 200000.00`.

Federal algorithm (legacy parity): `annualized = periodGross × divisor`; `taxable =
annualized − stdDeduction − federalAllowances × ALLOWANCE`; `taxable <= 0 → 0`; walk the
ladder (`base_tax + (taxable − bracket_min) × rate`); `periodTax = ROUND(annualTax /
divisor, 2) + ADDITIONAL_FED_WH`. State: `ROUND(periodGross × rate, 2)` (flat, allowances
ignored as legacy). FICA: `ROUND(MIN(periodGross, MAX(0, SS_WAGE_BASE − ytd)) × SS_RATE, 2)`;
Medicare: `ROUND(periodGross × MEDICARE_RATE, 2)` plus, when `ytd + periodGross >
MEDICARE_ADDL_THRESHOLD`, `ROUND((ytd >= threshold ? periodGross : ytd + periodGross −
threshold) × MEDICARE_ADDL_RATE, 2)` – literally `calculate_medicare`, with `ytd` the
TAX-YTD-01 quantity (which already contains this period's gross; the resulting
double-count is preserved for 2024 parity and listed as a known defect for Phase 5). **Zero-amount tax rows are not written** (legacy `IF v_tax > 0`),
so a `TX` employee has no `101` row and an employee above the wage base has no `102` row –
the shadow diff treats "absent" and "absent" as `MATCH`, never as `0.00`.

**TAX-YTD-01 (preserved quirk):** the YTD the engine feeds to the FICA / Medicare wage-base
tests is `SUM(AMOUNT)` of `ELEMENT_TYPE='EARNING'`, `STATUS='CALCULATED'` rows of *all*
runs (any run status) whose period starts in the tax year, **including the current run's
gross row already written** – exactly `get_ytd_earnings` called after the gross insert.
This is what makes 2024 FICA match to the cent; the *reporting* YTD on the payslip is the
corrected `APPROVED`/`PAID`-only figure (BUG-06).

### Declared shadow divergences (`ShadowDiffReport.explanation`)

`UNLISTED_STATE_FALLBACK` (legacy 5 % vs `MISSING_TAX_RATE` row), `HEAD_OF_HOUSEHOLD_ZERO_FED`,
`NON_2024_YEAR` (bracket table vs hard-coded), `LEGACY_PARTIAL_COMMIT` (legacy run aborted
between commits), `LEGACY_ERROR_ROW` (legacy `SQLERRM` row where Java calculated, or vice
versa, with matching cause). Anything else is `DIFF_UNEXPLAINED` and fails the
CUTOVER_PLAN.md §8.4 gate. Recorded legacy expectations live in
`tests/golden/payroll/<periodId>.json`, produced from the seed + `PKG_PAYROLL.pkb` by hand
(each file states its derivation) – there is no Oracle to record from.

`COMPONENT_MAPPING.md` §11 was **not** changed: `-20101 … -20104` and `-20001` were already
listed.
