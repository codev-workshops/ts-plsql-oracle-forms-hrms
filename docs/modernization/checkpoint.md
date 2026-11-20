# HRMS Modernization — Checkpoint

Working branch: `devin/1795150000-phased-modernization` (based on PR #75 head `fddc2ef`, whose Spring Boot +
Postgres skeleton and Leave pilot are reused and re-ratified below). Previous checkpoint (old phase numbering)
is kept at `checkpoint-2026-11-19-prior.md`. Source of truth = files in `plsql/`, `forms/`, `schema/`, `data/`
(the README overstates the system).

| Phase | Scope | Status |
|---|---|---|
| 0 Discover | error-code, Forms-rule, trigger, known-bug inventories | **accepted (this doc §0)** |
| 1 Define + pilot | conventions, foundation services, schema+seed, Leave E2E, contract v1.0 | in progress |
| 2 Execute | Employee/Org, Payroll, Performance, Integration, Reporting | pending |
| 3 Integrate | E2E journeys, menu parity, baselines diff, migration rehearsal, security closeout, runbook | pending |

---

## 0. Phase 0 — Discover

### 0.a RAISE_APPLICATION_ERROR inventory → target API errors

Every `RAISE_APPLICATION_ERROR` in `plsql/packages/*.pkb` and `plsql/triggers/*.sql` (43 call sites, 30 distinct codes).
API error body: `{ "code": <legacy code>, "error": "<SYMBOL>", "message": "...", "field": <optional> }`.

| Code | Source (file:line) | Legacy condition | API symbol | HTTP |
|---|---|---|---|---|
| -20001 | PKG_EMPLOYEE.pkb:365,385,418,436,774; PKG_LEAVE.pkb:92 | employee not found / not active (leave submit) / rehire target missing | `EMPLOYEE_NOT_FOUND` | 404 (422 when the employee exists but is not ACTIVE on leave submit) |
| -20002 | PKG_EMPLOYEE.pkb:332 | DUP_VAL_ON_INDEX on create (MAX()+1 race) | `DUPLICATE_EMP_NUMBER` | 409 (unreachable after the sequence fix; kept registered) |
| -20003 | PKG_EMPLOYEE.pkb:80 | invalid/inactive department | `INVALID_DEPARTMENT` | 422 |
| -20004 | PKG_EMPLOYEE.pkb:105,113 | invalid/inactive manager; circular reporting chain | `INVALID_MANAGER` | 422 |
| -20005 | PKG_EMPLOYEE.pkb:663 | already terminated | `ALREADY_TERMINATED` | 409 |
| -20010 | PKG_EMPLOYEE.pkb:204 | first/last name missing | `NAME_REQUIRED` | 400 |
| -20011 | PKG_EMPLOYEE.pkb:217 | invalid/inactive job | `INVALID_JOB` | 422 |
| -20012 | PKG_EMPLOYEE.pkb:526 | transfer of non-ACTIVE employee | `EMPLOYEE_NOT_ACTIVE` | 409 |
| -20101 | PKG_PAYROLL.pkb:31 | salary ≤ 0 | `SALARY_NOT_POSITIVE` | 400 |
| -20102 | PKG_PAYROLL.pkb:199,246 | period already closed / run on closed period | `PERIOD_CLOSED` | 409 |
| -20103 | PKG_PAYROLL.pkb:567 | approve run not CALCULATED | `INVALID_RUN_STATUS` | 409 |
| -20104 | PKG_PAYROLL.pkb:397 | no active salary for employee in run | `NO_ACTIVE_SALARY` | 422 (recorded per detail row as ERROR, run continues) |
| -20201 | PKG_LEAVE.pkb:150 | insufficient balance (ACCRUAL_FLAG='Y') | `INSUFFICIENT_BALANCE` | 422 |
| -20202 | PKG_LEAVE.pkb:142 | overlap with PENDING/APPROVED request | `LEAVE_OVERLAP` | 409 |
| -20203 | PKG_LEAVE.pkb:103,109 | invalid/inactive leave type; minimum tenure not met | `INVALID_LEAVE_TYPE` | 422 |
| -20204 | PKG_LEAVE.pkb:226,282,332 | approve/reject non-PENDING; cancel non-PENDING/APPROVED | `INVALID_LEAVE_STATUS` | 409 |
| -20210 | PKG_LEAVE.pkb:117 | start > end | `INVALID_DATE_RANGE` | 400 |
| -20211 | PKG_LEAVE.pkb:123 | start more than 5 days in the past | `BACKDATE_LIMIT` | 422 |
| -20212 | PKG_LEAVE.pkb:137 | 0 business days in range | `NO_BUSINESS_DAYS` | 422 |
| -20301 | PKG_SECURITY.pkb:50 | unknown user (and, in target, wrong password) | `INVALID_CREDENTIALS` | 401 |
| -20310 | PKG_SECURITY.pkb:219 | password < 8 chars | `PASSWORD_TOO_SHORT` | 400 |
| -20311 | PKG_SECURITY.pkb:223 | no uppercase | `PASSWORD_NO_UPPERCASE` | 400 |
| -20312 | PKG_SECURITY.pkb:227 | no digit | `PASSWORD_NO_DIGIT` | 400 |
| -20401 | PKG_PERFORMANCE.pkb:46 | open cycle not in DRAFT | `INVALID_CYCLE_STATUS` | 409 |
| -20402 | PKG_PERFORMANCE.pkb:110 | review missing / wrong status for self-assessment | `INVALID_REVIEW_STATUS` | 409 |
| -20403 | PKG_PERFORMANCE.pkb:143 | rating outside 1.0–5.0 | `RATING_OUT_OF_RANGE` | 400 |
| -20501 | trg_employees.sql:36 | hire date > today + 180 | `HIRE_DATE_TOO_FAR` | 422 |
| -20502 | trg_employees.sql:51 | email already used by an ACTIVE_FLAG='Y' employee | `EMAIL_IN_USE` | 409 |
| -20503 | trg_employees.sql:72 | direct TERMINATED→ACTIVE update | `REHIRE_REQUIRED` | 409 |
| -20504 | trg_employees.sql:127 | DELETE on EMPLOYEES | `DELETE_NOT_ALLOWED` | 405 (no DELETE route; DB trigger backstop) |
| -20900 | PKG_COMMON.pkb:124 | system parameter missing / not editable | `PARAM_NOT_EDITABLE` | 422 |

Service-only codes added (no legacy equivalent; numbered in the module's range):

| Code | Symbol | HTTP | Replaces |
|---|---|---|---|
| -20000 | `VALIDATION_FAILED` | 400 | Forms required-field MESSAGEs (e.g. "Please select a leave type.") |
| -20205 | `NOT_AUTHORIZED_APPROVER` | 403 | implicit: Forms only showed approvals routed to the user |
| -20213 | `INVALID_HALF_DAY` | 400 | half-day gap (§0.d B2) |
| -20302 | `SESSION_EXPIRED` | 401 | `is_session_valid` FALSE → Forms "Session expired." |
| -20303 | `ACCOUNT_LOCKED` | 423 | new lockout (§0.d B3) |
| -20304 | `FORBIDDEN` | 403 | `has_permission` FALSE → Forms "Access denied." / block lockdown |
| -20990 | `NOT_FOUND` | 404 | NO_DATA_FOUND surfacing as ORA-01403 |
| -20991 | `CONFLICT` | 409 | optimistic-lock / FOR UPDATE NOWAIT (ORA-00054) |

### 0.b Forms-only rules (not enforced in PL/SQL) → service-layer disposition

| # | Source | Rule | Target |
|---|---|---|---|
| F1 | HRMS_LEAVE.xml `BTN_CANCEL_REQUEST` WHEN-BUTTON-PRESSED | cancel only if STATUS ∈ {PENDING, APPROVED} | service guard → -20204 (also in package) |
| F2 | HRMS_LEAVE.xml `BTN_SUBMIT` | leave type, start date, end date required | request validation → -20000 with `field` |
| F3 | HRMS_LEAVE.xml WNFI DEFAULT_WHERE | `EMP_ID = :GLOBAL.current_emp_id ORDER BY CREATED_DATE DESC` (own requests only) | `GET /leave/requests` scoped to caller unless RBAC allows `empId` param; same ordering |
| F4 | HRMS_LEAVE.xml `LEAVE_REQUEST` block | Insert/Update/Delete not allowed (read-only list) | no PUT/DELETE on requests; state changes only via submit/approve/reject/cancel |
| F5 | HRMS_LEAVE.xml RG_LEAVE_TYPES | LOV = ACTIVE_FLAG='Y' ORDER BY LEAVE_TYPE_NAME | `GET /leave/types` same filter/order |
| F6 | HRMS_LEAVE.xml cancel call | cancel reason hard-coded 'Cancelled by employee' | default reason when body omits it |
| F7 | HRMS_EMPLOYEE.xml WNFI | if NOT has_permission(EMPLOYEE,'EDIT') → block insert/update/delete disabled | RBAC on create/update/transfer/terminate (see §1 RBAC) → -20304 |
| F8 | HRMS_EMPLOYEE.xml WNFI DEFAULT_WHERE | `EMPLOYMENT_STATUS='ACTIVE' AND ACTIVE_FLAG='Y'` default list | `GET /employees` default filter; `status=` param widens |
| F9 | HRMS_EMPLOYEE.xml WHEN-VALIDATE-ITEM EMAIL | `PKG_VALIDATION.validate_email_format` (= PKG_COMMON regex) | service validation → -20000 field `email` |
| F10 | HRMS_EMPLOYEE.xml WVI HIRE_DATE | hire date ≤ today + **90** (trigger says 180) | drift — resolved to 180 (§0.d B10) |
| F11 | HRMS_EMPLOYEE.xml WVI DEPT_ID / JOB_ID | dept/job must be ACTIVE | covered by -20003 / -20011 |
| F12 | HRMS_EMPLOYEE.xml PRE-INSERT / PRE-UPDATE | EMP_ID from SEQ_EMPLOYEE, EMP_NUMBER from generate_emp_number, ACTIVE_FLAG/STATUS defaults, CREATED_/MODIFIED_ audit columns from `:GLOBAL.current_user` | service owns IDs and audit columns (§1 conventions) |
| F13 | HRMS_EMPLOYEE.xml ON-ERROR | 40501 "record locked" → retry message | -20991 CONFLICT |
| F14 | HRMS_PAYROLL.xml WNFI | requires has_permission(PAYROLL,'VIEW') (grade ≥ 5) | RBAC → -20304 on all payroll routes |
| F15 | HRMS_PAYROLL.xml WNFI DEFAULT_WHERE | periods `STATUS='OPEN' ORDER BY PERIOD_START_DATE DESC` | `GET /payroll/periods` default filter |
| F16 | HRMS_PAYROLL.xml calculate button | only when run STATUS='PENDING' | service guard → -20103 |
| F17 | HRMS_PAYROLL.xml approve button | requires has_permission(PAYROLL,'APPROVE') (grade ≥ 8) | RBAC → -20304 |
| F18 | HRMS_PERFORMANCE.xml WNFI DEFAULT_WHERE | cycles `STATUS IN ('OPEN','DRAFT') ORDER BY CYCLE_YEAR DESC` | `GET /performance/cycles` default filter |
| F19 | HRMS_PERFORMANCE.xml GOAL_CATEGORY poplist | only BUSINESS/DEVELOPMENT/LEADERSHIP (DB allows 5) | mismatch — resolved (§0.d B6) |
| F20 | HRMS_PERFORMANCE.xml blocks | reviews: no insert/delete; goals: no delete | no DELETE routes; review create only via cycle generation |
| F21 | HRMS_MENU.xml WNFI | MI_PAYROLL / MI_ADMIN / MI_REPORTS disabled unless has_permission(…,'VIEW') | `GET /auth/menu` returns same enablement (Phase 3 parity test) |
| F22 | HRMS_MENU.xml BTN_PAYROLL / BTN_REPORTS | "Access denied." unless VIEW permission | -20304 on route |
| F23 | all forms WNFI / HRMS_COMMON_LIB.check_session | session must be valid (30 min) | auth middleware → -20302 |
| F24 | HRMS_LOGIN.xml BTN_LOGIN | username+password required; any failure → generic "Invalid username or password." | -20000 for missing; -20301 for every auth failure (no user enumeration) |
| F25 | HRMS_VALIDATION_LIB.validate_email | INSTR-based check (accepts `a@b.c`, rejects nothing the header claims) | retired; server regex canonical (§0.d B7) |
| F26 | HRMS_VALIDATION_LIB.validate_phone | TRANSLATE keeps letters, 10–11 chars | server rule: strip non-digits, 10–11 digits (PKG_COMMON) |
| F27 | HRMS_VALIDATION_LIB.validate_ssn | 9 digits and no 000/00/0000 group (server checks only 9 digits) | adopt stricter PLL rule in service |
| F28 | HRMS_VALIDATION_LIB.validate_date_not_future | date ≤ today | used for termination/birth dates |
| F29 | HRMS_VALIDATION_LIB.validate_salary_range | salary outside grade min/max → message, not blocking | `warnings[]` in response (soft, matches PKG_EMPLOYEE comment) |
| F30 | HRMS_COMMON_LIB.handle_error | logs via PKG_COMMON.log_error | global exception handler logs + correlation id |

Forms referenced but absent from the repo: HRMS_REPORTS, HRMS_ADMIN (menu targets). Reporting is built from PKG_REPORTING; Admin
is limited to system parameters (PKG_COMMON.set_param) in scope.

### 0.c Trigger disposition

| Trigger | Behaviour | Disposition |
|---|---|---|
| TRG_EMP_BEFORE_INSERT | default CREATED_BY/DATE, ACTIVE_FLAG='Y', STATUS='ACTIVE'; -20501 hire > +180d; -20502 email uniqueness | **move to service** (rules + audit columns); DB keeps column DEFAULTs and a partial unique index `lower(email) WHERE active_flag='Y'` as backstop |
| TRG_EMP_BEFORE_UPDATE | MODIFIED_*; -20503 TERMINATED→ACTIVE; EMPLOYEE_HISTORY rows for status/dept/job change | **move to service**; history written once by EmployeeService (legacy writes duplicate rows: package `log_history` + trigger). **Keep-in-DB** a minimal guard trigger for -20503 against direct SQL |
| TRG_EMP_INSTEAD_OF_DELETE | blocks DELETE (-20504) | **keep-in-DB** (Postgres BEFORE DELETE trigger) + no DELETE route |
| TRG_SALARY_AUDIT | AUDIT_LOG on salary insert/update/delete with JSON | **move to service** (SalaryRecordService → AuditService; one row per change, no double logging) |
| TRG_LEAVE_REQUEST_AUDIT | AUDIT_LOG 'STATUS_CHANGE' with old/new status | **move to service**; written as action 'UPDATE' with `{"status":…}` old/new (legacy value violates CHK_AUDIT_ACTION, §0.d B11) |
| TRG_DEPARTMENT_AUDIT | AUDIT_LOG on department DML | **move to service** (department admin endpoints) |

### 0.d Known-bugs register

| # | Bug (evidence) | Decision | Target behaviour |
|---|---|---|---|
| B1 | `PKG_EMPLOYEE.generate_emp_number` uses `MAX(SUBSTR(EMP_NUMBER,5))+1` (race; DUP → -20002) | **fix** | `nextval('seq_emp_number')` → `EMP-` + 6-digit pad; sequence set to `GREATEST(1000, max seeded + 1)` |
| B2 | PKG_LEAVE half-day: `total_days := 0.5` for any range; overlap check ignores AM/PM, so a PM half-day after an AM half-day is rejected, and a half-day flag on a multi-day range is accepted | **fix** | half-day requires start = end and period AM/PM (-20213); AM+PM on the same day allowed; same period or full-day overlap → -20202 |
| B3 | PKG_SECURITY: MD5 hashing; `authenticate` never verifies a password; no lockout; timing difference | **fix** | bcrypt (cost 12) in `user_credentials`; lockout after `SECURITY.MAX_FAILED_LOGINS` (default 5) for 15 min (-20303); constant-time dummy hash for unknown users |
| B4 | PKG_SECURITY hard-coded AES key `c_encryption_key` | **fix** | key from env/secret `HRMS_SSN_KEY` (AES-256-GCM); legacy key used only inside the one-off migration re-encryption step, never committed |
| B5 | PKG_PAYROLL 2024 constants (SS wage base 168600, 6.2%, 1.45%, +0.9% > 200000, std deductions 14600/29200, allowance 4300) and hard-coded 2024 brackets | **port as data** | `payroll_tax_parameters` + `tax_brackets` keyed by `tax_year`; 2024 rows seeded for parity; year chosen from pay date; open question Q1 for 2025/2026 values |
| B6 | HRMS_PERFORMANCE.xml goal-category poplist has 3 values; CHK_GOAL_CATEGORY allows 5 | **fix** | API accepts all 5 DB values; `GET /performance/goal-categories` lists 5 |
| B7 | Email validation drift: HRMS_VALIDATION_LIB INSTR check vs PKG_COMMON regex `^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$` (the PLL header's "rejects subdomains" claim is false; real drift = PLL accepts 1-char TLDs/invalid chars) | **fix** | PKG_COMMON regex is the single rule; stored as `lower(trim(email))` |
| B8 | PKG_EMPLOYEE ↔ PKG_PAYROLL circular dependency (real edge only EMP→PAYROLL.create_salary_record; header claims PAYROLL→EMP.is_active) and SECURITY→EMPLOYEE.set_session_context | **fix** | Employee publishes `EmployeeHired/Promoted/Rehired/Terminated` events; Payroll's listener creates/end-dates salary in the same transaction (orchestration, no compile-time cycle); session context = auth middleware |
| B9 | PKG_LEAVE auto-approve: inserts STATUS='APPROVED' then calls `approve_leave_request`, which raises -20204 (status ≠ PENDING) → every REQUIRES_APPROVAL='N' submit fails | **fix (intended rule)** | auto-approved request stored APPROVED, days go straight to USED, no manager notification |
| B10 | Hire-date cap drift: form 90 days, trigger 180 | **port trigger** | 180 days (-20501), per instruction (supersedes prior decision of 90) |
| B11 | TRG_LEAVE_REQUEST_AUDIT writes ACTION_TYPE='STATUS_CHANGE', violating CHK_AUDIT_ACTION | **fix** | action 'UPDATE' with old/new status JSON |
| B12 | PENDING/USED balance updates silently no-op when no LEAVE_BALANCES row for that year | **fix** | initialise balances for the year (initialize_balances) before update |
| B13 | `expire_carryover` double-subtracts if run twice | **fix** | idempotent (zeroes CARRYOVER_FROM_PREV in same statement, guarded) |
| B14 | `calculate_business_days` ignores observed holidays | **port** | holidays table is authoritative; observed dates must be seeded as rows (Q2) |
| B15 | `PKG_SECURITY.has_permission` comment says grade 5–7 "edit own department" but code returns FALSE for any non-VIEW action | **fix (per instruction)** | grade ≥ 5 may edit records in own department |
| B16 | `search_employees` builds dynamic SQL by concatenating `p_status` (SQL injection) | **fix** | parameterised queries only |
| B17 | `promote_employee` uses `ROWNUM = 1 … ORDER BY` (wrong current salary) | **fix** | latest active salary by effective_date |
| B18 | `reverse_payroll` has no status guard | **fix** | only APPROVED runs reversible (-20103) |
| B19 | Seed vs DDL column mismatches (LOCATIONS.PHONE, JOB_GRADES.GRADE_LEVEL/GRADE_CODE, SYSTEM_PARAMETERS.DESCRIPTION); sequences starting at 1 collide with seeded IDs | **fix in migration** | seed loader maps columns; every sequence `setval` to `GREATEST(START WITH, max(id)+1)` |
| B20 | Permission check uses `JOB_TITLES.GRADE_ID` (surrogate key) as the "grade" number | **port** | RBAC grade = `grade_id` for parity (seed grade IDs 1..10 equal levels); recorded as Q3 |

---

## 1. Phase 1 — Conventions (ratified)

To be completed with the Phase 1 unit.
