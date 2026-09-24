# P4 Payroll – `ApiError.code` contract

Source of truth for legacy numbers: `COMPONENT_MAPPING.md` §11. This file copies the
Phase 4 subset (`-20101` … `-20104` row and the shared `-20001`) and adds HTTP status,
triggering rule, raising component and `field`. **§11 was not modified** – every legacy
code below already exists there; framework codes (§2) are not `-20xxx` numbers and live
only here.

`code` is transmitted as a **string** carrying the Oracle `SQLCODE` verbatim (`"-20102"`)
so Level 2 parallel-run diffs (`legacy_source=recorded`, GOLDEN-ORACLE MODE = OFF) can
compare it with the `ORA-20xxx` recorded from `PKG_PAYROLL.pkb`.

## 1. Legacy codes this module may return

| `code` | HTTP | Raised by (target) | Legacy origin (`PKG_PAYROLL.pkb`) | Triggering rule | `field` | Routes |
|---|---|---|---|---|---|---|
| `-20101` | 400 (P3 route) / **detail row only** in P4 | `SalaryService.recordChange` (P3, `salary-module`); in P4 written as `PAYROLL_DETAILS.STATUS='ERROR'` row `errorCode` by `PayrollRunService` | `create_salary_record` `IF p_base_salary <= 0` | Salary ≤ 0. No P4 route creates salary rows, so no P4 route returns `-20101` as an HTTP failure; the `TaxEngine`/gross step defensively re-checks `BASE_SALARY > 0` of the active salary row and records an `ERROR` row with this code. Surfaces over HTTP only through `GET …/payslips/{empId}` → `422` (§1.1). | `null` | `calculate` (row), `payslips` (422) |
| `-20102` | 422 | `PayPeriodService.close`, `PayrollRunService.createRun` | `close_pay_period` / `create_payroll_run` `IF v_status = 'CLOSED'` | `PAY_PERIODS.STATUS = 'CLOSED'` when closing the period or creating a run in it. Only `CLOSED` is rejected (legacy parity: `OPEN`, `PROCESSING`, `REVERSED` are accepted by both operations). | `null` | `POST /periods/{periodId}/close`, `POST /periods/{periodId}/runs` |
| `-20103` | 422 | `PayrollRunService.approve` | `approve_payroll` `IF v_status NOT IN ('CALCULATED')` | `PAYROLL_RUNS.STATUS <> 'CALCULATED'` (`PENDING`, `CALCULATING`, `APPROVED`, `PAID`, `REVERSED`, `ERROR`). | `null` | `POST /runs/{runId}/approve` |
| `-20104` | **detail row**; 422 via payslip | `PayrollRunService.calculateEmployee` writes the `ERROR` row; `PayslipService` re-raises it | `calculate_employee_pay` `IF v_annual_salary = 0` (no `SALARY_RECORDS` row with `ACTIVE_FLAG='Y'` – the `NO_DATA_FOUND` handler set 0) | Employee in the run population has no active salary record on `PERIOD_END_DATE`. The run is **not** aborted (legacy `WHEN OTHERS` per employee preserved); one `STATUS='ERROR'` row, `errorCode='-20104'`, `errorMessage='No active salary record for employee {empId}'`. | `null` | `calculate` (row), `payslips` (422) |
| `-20001` | 404 | `EmployeeLookup` (shared, `employee-module`) | `PKG_EMPLOYEE` `NO_DATA_FOUND` → `-20001`; `get_payslip` joins `EMPLOYEES` and returns an empty cursor | `{empId}` of `GET …/payslips/{empId}` matches no `EMPLOYEES` row. **Declared divergence from P0/P3:** `TERMINATED` / `ACTIVE_FLAG='N'` employees are *not* rejected here – a terminated employee keeps access to historical payslips (`jwt.empId == {empId}`) and payroll staff can still open them. | `null` | `GET /runs/{runId}/payslips/{empId}` |

### 1.1 Failure rows vs HTTP failures

`POST /api/payroll/runs/{runId}/calculate` never returns a per-employee `-20xxx`: it
answers `202` and each failing employee becomes exactly one `PAYROLL_DETAILS` row

| column | value |
|---|---|
| `ELEMENT_ID` | `0` (sentinel `PAY_ELEMENTS` row `ELEMENT_CODE='ERROR'`, `ACTIVE_FLAG='N'`, added by the P4 migration so `FK_PD_ELEMENT` holds – in legacy this insert violated the FK, see README) |
| `ELEMENT_TYPE` | `'ERROR'` |
| `AMOUNT` | `0.00` |
| `STATUS` | `'ERROR'` |
| `ERROR_CODE` (target column) | `'-20104'`, `'-20101'`, `'MISSING_TAX_RATE'` or `'INTERNAL_ERROR'` |
| `ERROR_MESSAGE` | the frozen message below (legacy wrote `SQLERRM`) |

`GET …/details` returns these rows as `PayrollDetail{status:ERROR,errorCode,errorMessage}`;
`GET …/payslips/{empId}` for such an employee returns `422` with `ApiError.code` =
`errorCode`; `VW_PAYROLL_LATEST`, the register and the run totals exclude them
(`pd.STATUS <> 'ERROR'`).

Messages (frozen, English, must match `PKG_PAYROLL.pkb` for recorded-diffing):

| `code` | `message` |
|---|---|
| `-20101` | `Salary must be positive: {baseSalary}` |
| `-20102` | `Period already closed: {periodId}` |
| `-20103` | `Cannot approve run in status: {status}` |
| `-20104` | `No active salary record for employee {empId}` |
| `-20001` | `Employee not found or not active` |

## 2. Framework codes (no legacy equivalent)

UPPER_SNAKE_CASE; produced by `GlobalExceptionHandler` (P0) for conditions the Forms tier
never signalled with `RAISE_APPLICATION_ERROR` (or signalled as an unhandled
`NO_DATA_FOUND`). Not compared at Level 2.

| `code` | HTTP | Triggering rule | Routes |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean Validation on `PayrollRunCreateRequest`, `PayrollRunReverseRequest`, `PayPeriodListQuery`, `PayrollRunListQuery`, `PayrollDetailListQuery` (`validation-schema.json` module `p4-payroll`); unsupported `sort`, `status`, paging. `field` = first violation. | all with a body / query |
| `TOKEN_INVALID` | 401 | P0 token rules. | all |
| `FORBIDDEN` | 403 | `@PreAuthorize` denied: `PAYROLL:VIEW`, `PAYROLL:APPROVE`, `ADMIN:VIEW` (shadow), payslip self-scope, `includeBank=true` without `PAYROLL:APPROVE`. | all |
| `PERIOD_NOT_FOUND` | 404 | `{periodId}` matches no `PAY_PERIODS` row (legacy: unhandled `ORA-01403`). | `/periods/{periodId}/**` |
| `RUN_NOT_FOUND` | 404 | `{runId}` matches no `PAYROLL_RUNS` row. | `/runs/{runId}/**`, shadow |
| `PAYSLIP_NOT_FOUND` | 404 | Employee exists but has no `PAYROLL_DETAILS` row in the run (legacy: empty cursor). | `payslips` |
| `SHADOW_REPORT_NOT_FOUND` | 404 | Run has no `payroll_details_shadow` rows (never calculated by the Java engine). | shadow diff |
| `RUN_ALREADY_CALCULATING` | 409 | `calculate` while the run's batch job is still running (`STATUS='CALCULATING'`). Legacy would have started a second cursor loop over the same run. | `calculate` |
| `RUN_NOT_CALCULABLE` | 422 | `calculate` on `APPROVED`, `PAID` or `REVERSED` run. **Declared divergence** – legacy re-calculated any status. | `calculate` |
| `RUN_NOT_REVERSIBLE` | 422 | `reverse` on `PENDING`, `CALCULATING` or `REVERSED` run. **Declared divergence** – `reverse_payroll` had no guard. | `reverse` |
| `MISSING_TAX_RATE` | detail row (422 via payslip) | `TaxEngine` finds no active `tax_brackets` row for `(taxYear, stateCode)` or `(taxYear, filingStatus)`. **Declared divergence** – legacy defaulted unknown states to `0.05` (BUG-02). | `calculate` (row), `payslips` |
| `INTERNAL_ERROR` | 500 / detail row | Anything unmapped. On the calculation path it is recorded as the employee's `ERROR` row (`message` generic; details in `error_log` under `traceId`). | all |

## 3. Mapping contract

`GlobalExceptionHandler` (P0) is the only place that builds `ApiError`. P4 adds:

```java
// payroll-module
public class PeriodClosedException       extends HrmsException { /* -20102, 422 */ }
public class RunNotApprovableException   extends HrmsException { /* -20103, 422 */ }
public class NoActiveSalaryException     extends HrmsException { /* -20104, recorded as ERROR row; 422 when re-raised by PayslipService */ }
public class MissingTaxRateException     extends HrmsException { /* MISSING_TAX_RATE, recorded as ERROR row; 422 when re-raised */ }
public class PeriodNotFoundException     extends HrmsException { /* PERIOD_NOT_FOUND, 404 */ }
public class RunNotFoundException        extends HrmsException { /* RUN_NOT_FOUND, 404 */ }
public class PayslipNotFoundException    extends HrmsException { /* PAYSLIP_NOT_FOUND, 404 */ }
public class RunStateException           extends HrmsException { /* RUN_NOT_CALCULABLE | RUN_NOT_REVERSIBLE 422, RUN_ALREADY_CALCULATING 409 */ }
```

`-20101` reuses P3's `InvalidSalaryException`; `-20001` reuses the shared
`EmployeeNotFoundException` (`EmployeeLookup.findAnyById`, the non-active-filtering
variant introduced for payslips).
