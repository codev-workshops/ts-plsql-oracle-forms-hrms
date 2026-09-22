# P3 Employee – `ApiError.code` contract

Source of truth for legacy numbers: `COMPONENT_MAPPING.md` §11. This file copies the
Phase 3 subset (`-20001 … -20005`, `-20010 … -20012`, `-20101`, `-20104`, `-20501 … -20504`)
and adds the HTTP status, the triggering rule and the raising component. **§11 was not
modified** – it already lists every code below (rows `-20001`, `-20002`, `-20003 / -20011`,
`-20004`, `-20005`, `-20010`, `-20012`, `-20101 … -20104`, `-20501 … -20504`); this file only
pins *which* status each code takes where §11 gives several (`-20101 … -20104 | 400 / 422`,
`-20501 … -20504 | 400 / 409 / 422 / 405`). The framework codes in §2 are not `-20xxx`
numbers and therefore live only here (and in `ErrorCode`).

Everything in `contracts/p0-foundation/error-codes.md` §2 (`VALIDATION_FAILED`,
`TOKEN_INVALID`, `FORBIDDEN`, `INTERNAL_ERROR`, …) and §3 (`GlobalExceptionHandler`
mapping contract, `error_log` invariants) applies unchanged to `/api/employees/**`.

## 1. Legacy codes this module may return

`code` is transmitted as a **string** carrying the Oracle `SQLCODE` verbatim (`"-20003"`),
so Level 2 parallel-run diffs (TEST_STRATEGY.md §2.2, `legacy_source=recorded`) compare it
with the `ORA-20xxx` the package / trigger would have raised. "Raised by" names the owning
module (ARCH-01): `employee-service` for every code except `-20101` / `-20104`
(`salary-module`).

| `code` | HTTP | Raised by (target) | Legacy origin | Triggering rule | `field` |
|---|---|---|---|---|---|
| `-20001` | 404 | `EmployeeService` (all `/api/employees/{id}/**` routes, incl. the salary routes via the shared `EmployeeLookup`) | `PKG_EMPLOYEE.get_employee / update_employee / transfer_employee / terminate_employee` `NO_DATA_FOUND` → `-20001` | `{id}` matches no `employees` row. On the salary routes additionally: `POST …/salary` when the employee is not `ACTIVE`. Evaluated **before** the row-level scope rule, so `404` for every caller (the row is not scoped – `EMPLOYEE:VIEW` is universal). | `null` |
| `-20002` | 409 | `EmployeeService.create` | `create_employee` `DUP_VAL_ON_INDEX` on `UK_EMP_NUMBER` → `-20002` | Generated `empNumber` collides with an existing row. **Unreachable** with `SEQ_EMP_NUMBER` (BUG-01 fixed); kept so the Level 2 fixture that records the legacy `MAX()+1` race maps to a defined target code. Never expected in a recorded scenario. | `null` |
| `-20003` | 400 | `EmployeeService.create / transfer` | `validate_department`: `NO_DATA_FOUND` on `DEPARTMENTS … ACTIVE_FLAG='Y'` → `-20003` | `deptId` (create) / `deptId` (transfer) matches no `departments` row with `active_flag='Y'`. | `deptId` |
| `-20004` | 400 | `EmployeeService.create / update / transfer` (`assertAcyclicManagerChain`) | `validate_manager`: `NO_DATA_FOUND` on `EMPLOYEES … EMPLOYMENT_STATUS='ACTIVE' AND ACTIVE_FLAG='Y'` → `-20004` "Invalid or inactive manager"; `CONNECT BY` walk finds the subject → `-20004` "Circular reporting chain detected" | `managerEmpId` / `newManagerEmpId` (a) matches no `ACTIVE`/`active_flag='Y'` employee, (b) equals the subject `{id}`, or (c) is in the subject's downward reporting chain (walk up from the proposed manager reaches the subject; `≤ 50` levels, deeper is treated as circular – DATA-02 guard). Same code, two frozen messages. | `managerEmpId` / `newManagerEmpId` |
| `-20005` | 422 | `EmployeeService.terminate` | `terminate_employee`: `IF v_rec.EMPLOYMENT_STATUS = 'TERMINATED' THEN -20005` | Subject already has `employmentStatus = TERMINATED` (idempotency guard for a second terminate call). | `null` |
| `-20010` | 400 | Bean Validation (`@NotBlank`, `hrms-validation`) surfaced with the legacy code by `GlobalExceptionHandler` | `validate_employee`: `IF p_first_name IS NULL OR p_last_name IS NULL THEN -20010` | `firstName` or `lastName` is `null` or blank after trim (`EmployeeRules.blankToNull`) on `POST /api/employees` or `PUT /api/employees/{id}`. Pre-empts every other rule of the request. | `firstName` / `lastName` |
| `-20011` | 400 | `EmployeeService.create / update / transfer` | `validate_employee`: `NO_DATA_FOUND` on `JOB_TITLES … ACTIVE_FLAG='Y'` → `-20011` | `jobId` (create/update) / `newJobId` (transfer) matches no `job_titles` row with `active_flag='Y'`. | `jobId` / `newJobId` |
| `-20012` | 422 | `EmployeeService.transfer` | `transfer_employee`: `IF v_old_rec.EMPLOYMENT_STATUS <> 'ACTIVE' THEN -20012` | Subject's `employmentStatus <> ACTIVE` (`ON_LEAVE`, `SUSPENDED`, `TERMINATED`). Evaluated before any reference check. | `null` |
| `-20101` | 400 | Bean Validation (`@DecimalMin("0.01")`) on `initialSalary` / `baseSalary`, surfaced with the legacy code; `SalaryService.create` re-checks | `PKG_PAYROLL.create_salary_record`: `IF p_base_salary <= 0 THEN -20101` | `initialSalary` (`POST /api/employees`, when present) or `baseSalary` (`POST …/salary`) is `<= 0`. | `initialSalary` / `baseSalary` |
| `-20104` | 400 | `SalaryService.current` (`GET /api/employees/{id}/salary`) | `PKG_PAYROLL` (`calculate_payroll` / `get_current_salary`): `NO_DATA_FOUND` on `SALARY_RECORDS … ACTIVE_FLAG='Y'` → `-20104` | Employee exists but has no `salary_records` row with `active_flag='Y'` (never given an initial salary, or terminated – termination closes the active row). `GET …/salary/history` returns `[]` instead. | `null` |
| `-20501` | 400 | Bean Validation (`@HireDateWithinLimit`) surfaced with the legacy code | `TRG_EMP_BEFORE_INSERT`: `IF :NEW.HIRE_DATE > SYSDATE + 180 THEN -20501` (Forms `WHEN-VALIDATE-ITEM` used `+ 90`) | `hireDate > today + HR.MAX_FUTURE_HIRE_DAYS` (`90`, VAL-01 resolved to the **Forms** value, TEST_STRATEGY.md §4). Only on `POST /api/employees` (`hireDate` is not updatable). | `hireDate` |
| `-20502` | 409 | `EmployeeService.create / update` (`existsByEmailIgnoreCaseAndActiveFlag`, backed by the partial unique index on `UPPER(email) WHERE active_flag='Y'`) | `TRG_EMP_BEFORE_INSERT`: `SELECT COUNT(*) … WHERE UPPER(EMAIL) = UPPER(:NEW.EMAIL) AND ACTIVE_FLAG='Y'` → `-20502` | Another employee with `active_flag='Y'` has the same e-mail, case-insensitively. On `PUT` the subject itself is excluded (closes the SEC-10 update gap – the trigger only fired on insert). | `email` |
| `-20503` | 422 | `EmployeeService.update`, `DependentService`, `EmergencyContactService` (any write against a `TERMINATED` subject) | `TRG_EMP_BEFORE_UPDATE`: `IF :OLD.EMPLOYMENT_STATUS = 'TERMINATED' AND :NEW.EMPLOYMENT_STATUS = 'ACTIVE' THEN -20503` | Legacy: direct `TERMINATED → ACTIVE` status flip. Target: `employmentStatus` is not writable at all, so the rule generalises to "any `PUT /api/employees/{id}`, `POST/PUT …/dependents`, `POST/PUT …/contacts` against a `TERMINATED` employee". Rehire is a separate (P5) process. `transfer` uses `-20012`, `terminate` uses `-20005` instead. | `null` |
| `-20504` | 405 | `GlobalExceptionHandler` (`HttpRequestMethodNotSupportedException` on `/api/employees/**`) | `TRG_EMP_INSTEAD_OF_DELETE`: `-20504` | Any `DELETE` request under `/api/employees/**` (no `DELETE` route is mounted; the proxy forwards the verb unchanged at `employee=NEW`). Removal is termination (`POST …/terminate`) or `active=false` on a dependent / contact `PUT`. | `null` |

Frozen `message` strings (Level 2 compares them verbatim; `{…}` rendered as Oracle renders
`NUMBER || VARCHAR2`):

| `code` | `message` |
|---|---|
| `-20001` | `Employee not found or not active` (P0/P2 wording; the legacy `: {empId}` suffix is **not** appended) |
| `-20002` | `Duplicate employee number generated. Please retry.` |
| `-20003` | `Invalid or inactive department: {deptId}` |
| `-20004` | `Invalid or inactive manager: {managerEmpId}` **or** `Circular reporting chain detected: Employee {empId} cannot report to {managerEmpId}` (self-assignment uses the circular wording) |
| `-20005` | `Employee {empId} is already terminated` |
| `-20010` | `First name and last name are required` |
| `-20011` | `Invalid or inactive job: {jobId}` |
| `-20012` | `Cannot transfer non-active employee. Status: {employmentStatus}` |
| `-20101` | `Salary must be positive: {baseSalary}` |
| `-20104` | `No active salary record for employee {empId}` |
| `-20501` | `Hire date cannot be more than 90 days in the future` (legacy trigger said `180`; the limit is the single `HR.MAX_FUTURE_HIRE_DAYS` parameter and the message renders its value) |
| `-20502` | `Email address already in use: {email}` (e-mail as submitted, after trim) |
| `-20503` | `Cannot directly reactivate a terminated employee. Use the rehire process.` |
| `-20504` | `Direct deletion not allowed. Use termination process or set ACTIVE_FLAG to N.` |

## 2. Framework codes added by this phase (no legacy equivalent)

| `code` | HTTP | Triggering rule | `message` |
|---|---|---|---|
| `DEPENDENT_NOT_FOUND` | 404 | `{dependentId}` matches no active `employee_dependents` row **of `{id}`** (a dependent of another employee is also 404, never 403 – no cross-employee existence leak). | `Dependent not found` |
| `CONTACT_NOT_FOUND` | 404 | `{contactId}` matches no active `emergency_contacts` row of `{id}`. | `Emergency contact not found` |
| `CONFLICT` | 409 | `If-Match` on `PUT /api/employees/{id}` does not equal the row's current `version` (`ObjectOptimisticLockingFailureException`). | `Record was changed by another user` |
| `PRECONDITION_REQUIRED` | 428 | `PUT /api/employees/{id}` without an `If-Match` header. | `If-Match header is required` |
| `MODULE_READ_ONLY` | 409 | A write route of this file is reached while the proxy flag is `employee=NEW_READONLY` (the React UI hides write controls at that stage; this is the backend guard). | `Employee module is read-only during cutover` |

`FORBIDDEN` (403) additionally covers the row-level scope rules of this module: reading
another employee's salary / salary history / history salary columns without `PAYROLL:VIEW`
or `EMPLOYEE:EDIT`; reading or writing another employee's dependents or emergency contacts
without `EMPLOYEE:EDIT`.

`VALIDATION_FAILED` (400) additionally covers: every rule of the `p3-employee` module of
`validation-schema.json` that has no legacy code (lengths, `@Email` format, phone digit
count, `@Ssn` shape / all-zero groups, enum values, `countryCode` / `locationCode` /
`currencyCode` patterns, `priorityOrder` range, `dateOfBirth` in the future); unknown body
properties (`empNumber`, `employmentStatus`, `hireDate` on `PUT`, … –
`additionalProperties: false`); `hireDateFrom > hireDateTo`; an inactive `locationCode`
(`field = locationCode`); `effectiveDate < hireDate` on terminate / salary change, or
`effectiveDate < current.effectiveDate` on salary change (`field = effectiveDate`); paging
outside `Size`; an unsupported `fields` projection.

## 3. Order of evaluation (frozen – Level 2 fixtures depend on it)

1. Authentication (`401 TOKEN_INVALID`)
2. `@PreAuthorize` authority (`403 FORBIDDEN`)
3. Module flag (`409 MODULE_READ_ONLY`) – write routes only
4. Required headers (`428 PRECONDITION_REQUIRED`) – `PUT /api/employees/{id}` only
5. Bean Validation of body / query (`400 VALIDATION_FAILED`, `400 -20010`, `400 -20501`,
   `400 -20101`)
6. Subject existence (`404 -20001`), then sub-resource existence (`404 DEPENDENT_NOT_FOUND` /
   `CONTACT_NOT_FOUND`)
7. Row-level scope (`403 FORBIDDEN`)
8. Subject lifecycle state: `422 -20503` (update / dependents / contacts), `422 -20012`
   (transfer), `422 -20005` (terminate), `404 -20001` (salary change on a non-`ACTIVE`
   employee), `400 -20104` (current salary)
9. Reference rules in `PKG_EMPLOYEE.validate_employee` order: `-20003` (department) →
   `-20011` (job) → `-20004` (manager: inactive, then self, then circular) → inactive
   `locationCode` (`VALIDATION_FAILED`)
10. Uniqueness: `409 -20502` (e-mail), `409 -20002` (unreachable)
11. Optimistic lock: `409 CONFLICT`

## 4. Declared divergences from the legacy behaviour (Level 2 whitelist – exactly these)

| Id | Case | Legacy result | Target result |
|---|---|---|---|
| BUG-01 | Two concurrent hires | `MAX(EMP_NUMBER)+1` → `-20002` on the loser (or a duplicate number when `UK_EMP_NUMBER` is absent) | `SEQ_EMP_NUMBER` → both succeed with distinct `EMP-nnnnnn` |
| BUG-03 | Transfer / job change / termination history row | `TRG_EMP_BEFORE_UPDATE` writes `STATUS_CHANGE` / `DEPARTMENT_CHANGE` / `JOB_CHANGE` into columns that do not exist in the `EMPLOYEE_HISTORY` DDL (trigger fails or is disabled) | One `TRANSFER` / `PROMOTION` / `TERMINATION` row against the real columns (`old_/new_dept_id`, `old_/new_job_id`, `old_/new_manager_id`, `old_/new_salary`, `old_/new_location`, `reason_code`, `comments`) |
| BUG-07 | Terminate an employee who is logged in | Session stays valid, `USER_ACCOUNTS` untouched | `AuthService.revokeSessions(empId)`; `user_accounts.active_flag = 'N'` |
| VAL-01 | `hireDate` between `today + 91` and `today + 180` | Forms rejected (90), API / trigger accepted (180) | `400 -20501` (single limit 90) |
| VAL-02 | E-mail with a sub-domain (`a@mail.acme.com`) | `HRMS_VALIDATION_LIB.pll` rejected in Forms; `PKG_COMMON.is_valid_email` accepted via API | Accepted (`@Email`) |
| VAL-03 | Phone with 11 digits / with formatting characters | `.pll` rejected 11 digits; `PKG_COMMON.is_valid_phone` accepted 10–11 after stripping | Accepted (server rule) |
| SSN-ECHO | Read an employee | `VW_EMPLOYEE_DETAILS` / `EMPLOYEE` block exposed `SSN_ENCRYPTED` (and the decrypted value in the form) | `ssnLast4` only, scoped to `EMPLOYEE:EDIT` / self |
| SEC-10 | `PUT` an e-mail already used by another active employee | Accepted (trigger only fired on insert) | `409 -20502` |

No other row may be whitelisted. The cases below are inputs the legacy code either never
receives (Forms rejected them, or no caller existed) or fails on in an undefined way; the
scenario registry records the *target* expectation (`legacy_source=recorded`, provenance
`contract`) and a Level 2 diff there is a defect in the implementation, not a whitelist entry.

## 5. Contract rules for inputs outside the legacy scenario set

| Id | Case | Legacy result | Contract result |
|---|---|---|---|
| LIFECYCLE-01 | `PUT /api/employees/{id}` on a `TERMINATED` employee (any field) | Trigger only blocked the `TERMINATED → ACTIVE` flip; other edits succeeded | `422 -20503` |
| LIFECYCLE-02 | Body carries `employmentStatus`, `empNumber`, `hireDate`, `deptId`, `terminationDate` on `PUT` | Forms allowed editing `EMPLOYMENT_STATUS` / `HIRE_DATE` in place | `400 VALIDATION_FAILED` (unknown property) |
| LIFECYCLE-03 | `DELETE /api/employees/{id}` | Forms "delete" set `ACTIVE_FLAG='N'` client-side, then `CLEAR_RECORD` | `405 -20504`; no row changes |
| SCOPE-01 | Read another employee's salary without `PAYROLL:VIEW` / `EMPLOYEE:EDIT` | Visible to anyone who could open the form | `403 FORBIDDEN` |
| SCOPE-02 | Read / write another employee's dependents or contacts without `EMPLOYEE:EDIT` | No form existed (tables had no UI) | `403 FORBIDDEN` |
| SALARY-01 | `POST …/salary` with `effectiveDate` earlier than the current active record's `effectiveDate` | Accepted; two overlapping rows, `CHANGE_PCT` computed against the wrong "previous" | `400 VALIDATION_FAILED` on `effectiveDate` |
| SALARY-02 | `POST …/salary` outside the job grade band | `validate_salary_range` advisory / not called | `201`, `outOfGradeBand = true` (never an error) |
| SALARY-03 | `POST …/salary` on a non-`ACTIVE` employee | `create_salary_record` did not check status | `404 -20001` ("not found or not active") |
| MANAGER-01 | `managerEmpId == id` | Accepted at insert, `-20004` circular only on the later `CONNECT BY` | `400 -20004` (circular wording) |
| CONCURRENCY-01 | Two `PUT`s on the same version | Forms `FRM-40501` (row locked) | Second gets `409 CONFLICT` |

Everything else – including the `TRANSFER` row being written even when the target
department equals the current one, `-20005` on a repeated terminate, and `POST …/salary`
closing the previous row with `end_date = effectiveDate` – is preserved byte-for-byte; a
diff there is a defect.
