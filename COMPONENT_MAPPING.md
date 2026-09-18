# Component Mapping: Oracle Forms -> Spring Boot + Angular

Target repository layout assumed by this document (new repos or sub-folders; names are proposals):

```
hrms-api/  src/main/java/com/acme/hrms/<module>/{api,service,domain,persistence}/...
hrms-web/  src/app/<module>/...
db/        Flyway migrations for the HRMS schema (V__ scripts); utPLSQL tests for retained packages
```

Conventions used in every mapping:

- **Forms `:GLOBAL.current_user / current_emp_id / session_id`** -> `SecurityContextHolder` principal (`HrmsPrincipal { empId, username, roles }`) injected into services. Never passed from the client.
- **Forms `has_permission` checks in triggers** -> `@PreAuthorize("@perm.can('PAYROLL','APPROVE')")` on controller methods; UI hides/disables via a `PermissionService` fed from `/api/me`.
- **`check_session` per form** -> JWT validation + optional `USER_SESSIONS` row per login for audit continuity.
- **Forms LOV / record group** -> `GET /api/lookups/{name}` returning `{code,label}` lists, cached client-side; Angular `mat-autocomplete`/`mat-select`.
- **Forms `POST-QUERY` display-item lookups** (dept name, job title, manager name) -> joined in the read model SQL/DTO, never N+1.
- **Forms `EXECUTE_QUERY` on a block with default `WHERE`** -> paged `GET` endpoint with `Pageable` and explicit filters.
- **Forms row locking (`ON-LOCK`, `FOR UPDATE`, `FRM-40501`)** -> optimistic locking on `MODIFIED_DATE` (or a new `VERSION` column) with HTTP 409 and a UI "record changed, reload" dialog.
- **`MESSAGE(...)` + `RAISE FORM_TRIGGER_FAILURE`** -> `HrmsException(code, message)` -> RFC 7807 `ProblemDetail`; `RAISE_APPLICATION_ERROR(-20xxx)` codes are mapped 1:1 to API error codes so behaviour stays testable (see table at end).
- **`PKG_COMMON.log_error` autonomous logging** -> SLF4J + `AUDIT_LOG` write through `AuditService` in a `REQUIRES_NEW` transaction.
- **Confirm-on-exit / `:SYSTEM.FORM_STATUS = 'CHANGED'`** -> Angular `CanDeactivate` guard on dirty reactive forms.

---

## 1. `HRMS_LOGIN`

| | |
|---|---|
| Source | `forms/xml-exports/HRMS_LOGIN.xml`; `plsql/packages/PKG_SECURITY.pks/.pkb` (`authenticate`, `logout`, `is_session_valid`); `schema/tables/04_performance_tables.sql` (`USER_SESSIONS`) |
| Target (web) | `hrms-web/src/app/auth/login.component.ts` (only if local login is kept; with OIDC this becomes a redirect page), `hrms-web/src/app/auth/auth.interceptor.ts`, `auth.guard.ts` |
| Target (api) | `hrms-api/.../security/SecurityConfig.java` (resource server), `security/api/SessionController.java` (`POST /api/sessions`, `DELETE /api/sessions/current`, `GET /api/me`), `security/service/SessionService.java`, `security/persistence/UserSessionRepository.java` |
| Target (db) | `db/V1__user_roles.sql` (new `HRMS_ROLES`, `HRMS_USER_ROLES`); drop reliance on `PKG_SECURITY.authenticate` |

Business rules to preserve:
- A login resolves to exactly one **active** employee by e-mail (`UPPER(EMAIL)`, `EMPLOYMENT_STATUS='ACTIVE'`); ambiguous e-mails currently take `MIN(EMP_ID)` - replace with a hard failure plus data fix.
- Login creates a `USER_SESSIONS` row (`EMP_ID, USERNAME, LOGIN_TIME, IP_ADDRESS, SESSION_STATUS='ACTIVE'`) and an `AUDIT_LOG` entry; logout sets `LOGOUT_TIME`, `SESSION_STATUS='LOGGED_OUT'`.
- 30-minute inactivity timeout (`SYSTEM_PARAMETERS.SESSION_TIMEOUT_MIN`), measured on server time.
- Do **not** preserve: password check (absent), MD5 hashing, cleartext transmission, "use first employee on duplicate e-mail".

Data access to convert:
- `SELECT EMP_ID FROM EMPLOYEES WHERE UPPER(EMAIL)=UPPER(:u) AND EMPLOYMENT_STATUS='ACTIVE' AND ROWNUM=1` in the form trigger -> `EmployeeRepository.findActiveByEmail()` executed once at token exchange, result cached in the principal.
- `PKG_SECURITY.authenticate` -> OIDC token exchange (IdP does credential verification); `SessionService.open()` writes `USER_SESSIONS` via `JdbcTemplate`.
- `PKG_SECURITY.is_session_valid` -> JWT `exp` + sliding refresh; keep `USER_SESSIONS.LAST_ACTIVITY` update via a lightweight filter if audit needs it.

UI elements to recreate:
- Username/password items with `ConcealData` -> IdP hosted login page (recommended) or Angular Material form with `type=password`.
- `ERROR_MSG` display item (red, 3-line) -> `MatSnackBar` / inline `mat-error`.
- `KEY-ENTER` on password -> form submit on Enter (native).
- "Contact IT Support" hint text -> static footer.

Security notes: enforce lockout/rate-limiting at the IdP; log failed attempts without the username value in plaintext logs; forbid the username enumeration difference noted in source (`-20301` for both paths already, keep uniform timing).

---

## 2. `HRMS_MENU` (+ `forms/menus/HRMS_MENU.mmb.sql`)

| | |
|---|---|
| Source | `forms/xml-exports/HRMS_MENU.xml`; `forms/menus/HRMS_MENU.mmb.sql`; `PKG_SECURITY.has_permission` |
| Target (web) | `hrms-web/src/app/shell/app-shell.component.ts` (sidenav + toolbar), `shell/nav.config.ts`, `core/permission.service.ts`, `core/permission.directive.ts` (`*hrmsCan="'PAYROLL','VIEW'"`), `app.routes.ts` with `canMatch` guards |
| Target (api) | `security/api/MeController.java` (`GET /api/me` -> `{ empId, displayName, roles, permissions[] }`), `security/service/PermissionPolicy.java` (port of `has_permission`) |

Business rules to preserve:
- Module visibility: Payroll needs `PAYROLL/VIEW`, Admin `ADMIN/VIEW`, Reports `REPORTS/VIEW`; Employee, Leave, Performance visible to everyone (current form only checks the three).
- `has_permission` semantics as actually coded (`PKG_SECURITY.pkb`, keyed on `JOB_TITLES.GRADE_ID`): grade >= 8 -> `TRUE` for every module/action; grade >= 5 -> `TRUE` for any `VIEW`; `LEAVE` + `CREATE|VIEW` -> `TRUE` for everyone; `EMPLOYEE` + `VIEW` -> `TRUE` for everyone; everything else `FALSE` (the "edit own department" rule in the comment is **not** implemented, and leave approval is therefore grade >= 8 only). Port to `PermissionPolicy` exactly as coded for the characterization test, **then** migrate to explicit roles (`HR_ADMIN`, `PAYROLL_ADMIN`, `MANAGER`, `EMPLOYEE`) with grade-based defaults seeded once and manager-of-record rules added for leave/performance approvals.
- Logout closes the session (`PKG_SECURITY.logout`) and returns to login.
- Footer shows logged-in user, e-mail, and `APP_VERSION` from `SYSTEM_PARAMETERS`.

Data access to convert:
- `SELECT ... FROM EMPLOYEES e JOIN DEPARTMENTS d JOIN JOB_TITLES j WHERE EMP_ID = :GLOBAL.current_emp_id` in `WHEN-NEW-FORM-INSTANCE` -> part of `/api/me`.
- `SYSTEM_PARAMETERS` reads (`PKG_COMMON.get_param`) -> `SystemParameterService` with cache.

UI elements to recreate:
- Six large module buttons -> sidenav entries + dashboard tiles; keyboard mnemonics (`Alt+E` etc. from `.mmb`) -> optional hotkeys.
- Classic Forms toolbar (Save / Clear / Enter-Query / Execute-Query / navigation / Count Hits / Fetch Next Set) **is not recreated as-is**: each replaced by page-level Save/Cancel, filter panel, pagination, and a "records found" count on list pages.
- Help > About -> version dialog reading `/api/system/info`.
- `HRMS_REPORTS` and `HRMS_ADMIN` targets (*modules not in repo*): route placeholders `reports/` and `admin/`; implement as APEX links or Angular pages in Phase 5.

---

## 3. `HRMS_EMPLOYEE`

| | |
|---|---|
| Source | `forms/xml-exports/HRMS_EMPLOYEE.xml`; `plsql/packages/PKG_EMPLOYEE.pks/.pkb`; `plsql/triggers/trg_employees.sql`; `forms/libraries/HRMS_VALIDATION_LIB.pll.sql`; `plsql/packages/PKG_VALIDATION.pkb`; `schema/views/hrms_views.sql` (`VW_ACTIVE_EMPLOYEES`, `VW_ORG_HIERARCHY`, `VW_EMPLOYEE_COMPENSATION`) |
| Target (web) | `hrms-web/src/app/employee/employee-search.component.ts`, `employee-detail.component.ts` (tabbed: personal / job & compensation / dependents / history), `employee-form.component.ts`, `lifecycle/{transfer,promote,terminate,rehire}-dialog.component.ts`, `org-chart.component.ts` |
| Target (api) | `employee/api/EmployeeController.java`, `EmployeeLifecycleController.java`, `EmployeeSearchController.java`, `OrgController.java`; `employee/service/EmployeeFacade.java` (Phase 3: `SimpleJdbcCall` into `PKG_EMPLOYEE`), `EmployeeSearchService.java` (Java, bound params), `employee/service/EmployeeValidator.java`; `employee/persistence/EmployeeReadRepository.java` |
| Target (db) | `db/V2__pkg_employee_fixes.sql` (sequence-based `generate_emp_number`, remove `search_employees` from spec, break payroll cycle - see RISK R-02) |

Business rules to preserve (source of truth = package, then trigger, then form):
- Create: department active; manager exists, active, and assignment does not create a cycle (walk `MANAGER_EMP_ID` up to depth 15); names `UPPER(TRIM())`, e-mail `LOWER(TRIM())`; location defaults from department; status `ACTIVE`, `ACTIVE_FLAG='Y'`; `EMP_NUMBER = 'EMP-' || LPAD(n,6,'0')`; if salary given, create `SALARY_RECORDS` row; write `EMPLOYEE_HISTORY('HIRE')`, `AUDIT_LOG`, welcome + manager notifications.
- Update: same validations; department/job/manager/status changes create `EMPLOYEE_HISTORY` rows (`DEPARTMENT_CHANGE`, `JOB_CHANGE`, `STATUS_CHANGE`) - currently done by `TRG_EMP_BEFORE_UPDATE`; keep trigger during coexistence, port to service later.
- Hire date: form says <= today+90, trigger says <= today+180 -> **decision required**; recommend 90 in API, keep trigger at 180 as backstop.
- E-mail unique among `ACTIVE_FLAG='Y'` (trigger `-20502`), format per `PKG_COMMON.is_valid_email` regex.
- Terminate: no direct `TERMINATED -> ACTIVE` update (`-20503`); use `rehire_employee`. Termination cascade: cancel `PENDING` leave, close open salary record (`END_DATE`, `ACTIVE_FLAG='N'`), deactivate `EMPLOYEE_PAY_ELEMENTS`, history, audit, manager notification.
- Delete is forbidden (`-20504`); UI offers "Deactivate" / "Terminate" only.
- Salary must fall within `JOB_GRADES.MIN_SALARY..MAX_SALARY` for the job's grade (`PKG_VALIDATION.validate_salary_for_grade`, `HRMS_VALIDATION_LIB.validate_salary_range`).
- Phone 10-11 digits; SSN format and not all-zero groups (client lib) - SSN input only on a separate, permission-gated dialog; never returned unmasked (`PKG_COMMON.format_ssn_masked`).
- Protected fields when record exists: `EMP_NUMBER`, `HIRE_DATE`, `CREATED_*` read-only (`FRM-40200` handling).

Data access patterns to convert:
- Direct base-table blocks (`EMPLOYEE`, `SALARY`, `DEPENDENTS`, `EMERGENCY_CONTACTS`, `EMP_HISTORY`) with Forms-generated DML -> **no direct DML from UI**; writes go through `PKG_EMPLOYEE.create_employee/update_employee/...` (Phase 3) via `SimpleJdbcCall`; dependents/emergency contacts get thin Java repositories (no package exists for them today).
- Default `WHERE ACTIVE_FLAG='Y'` + `EXECUTE_QUERY` -> `GET /api/employees?status=ACTIVE&dept=&lastName=&page=`; backed by Java SQL with bound parameters replacing `search_employees` concatenation.
- `POST-QUERY` lookups (dept name, job title, manager name) -> single SELECT joining `DEPARTMENTS`, `JOB_TITLES`, self-join `EMPLOYEES` (or use `VW_ACTIVE_EMPLOYEES`).
- LOV record groups (`RG_DEPARTMENTS`, `RG_JOB_TITLES`, `RG_MANAGERS`, `RG_LOCATIONS`) -> `/api/lookups/departments|job-titles|managers|locations` (active only, same ORDER BY).
- `SEQ_EMPLOYEE.NEXTVAL` in `PRE-INSERT` -> inside `create_employee` (already), remove from UI path.
- `PKG_EMPLOYEE.get_org_chart` (`CONNECT BY`) -> keep as SQL (recursive CTE `WITH ... CONNECT BY` or `VW_ORG_HIERARCHY`) behind `GET /api/org/chart?root=`; paginate/lazy-load children in the UI to avoid the >500-employee timeout noted in source.
- `%ROWTYPE` returns (`t_emp_rec`) -> `EmployeeDto` record; ref cursors (`get_direct_reports`, `get_headcount_by_dept`) -> `RowMapper`s.

UI elements to recreate:
- Search bar (last name / dept / status) + results grid replacing enter-query mode; row count badge (was `COUNT_QUERY`).
- Detail page with 4 tabs (Personal, Job & Compensation, Dependents, Employment History); Job tab shows current salary (from `VW_EMPLOYEE_COMPENSATION`) read-only, with "Change salary" action calling `create_salary_record`.
- 8 LOVs -> autocomplete/select; radio/lists for gender, marital status, employment type/status.
- Lifecycle actions as explicit dialogs (Transfer, Promote, Terminate, Rehire) instead of editing status/department fields inline.
- Dirty-check on navigation; concurrency conflict dialog.
- History tab: `EMPLOYEE_HISTORY` list + `PKG_AUDIT.get_change_history` drawer.

---

## 4. `HRMS_LEAVE`

| | |
|---|---|
| Source | `forms/xml-exports/HRMS_LEAVE.xml`; `plsql/packages/PKG_LEAVE.pks/.pkb`; `schema/tables/03_leave_tables.sql`; `VW_LEAVE_SUMMARY`, `VW_PENDING_APPROVALS`; `trg_audit.sql` (`TRG_LEAVE_REQUEST_AUDIT`) |
| Target (web) | `hrms-web/src/app/leave/my-requests.component.ts`, `submit-request.component.ts`, `approvals.component.ts`, `team-calendar.component.ts`, `balance-card.component.ts` |
| Target (api) | `leave/api/LeaveRequestController.java` (`GET/POST /api/leave/requests`, `POST .../{id}:approve|reject|cancel`), `LeaveBalanceController.java`, `TeamCalendarController.java`; `leave/domain/{LeaveRequest,LeaveBalance,LeaveType,BusinessDayCalculator}.java`; `leave/service/LeaveRequestService.java`, `LeaveAccrualJob.java`, `CarryoverJob.java`; `leave/persistence/*Repository.java` (Spring Data JDBC) |
| Target (db) | `db/V3__leave_idempotency.sql` (unique key on `LEAVE_ACCRUAL_LOG`, `CARRYOVER_EXPIRED_FLAG` or expiry log) |

Business rules to preserve:
- Submit: employee active; leave type active; tenure >= `MIN_TENURE_DAYS`; `START_DATE <= END_DATE`; start not more than 5 days in the past; `TOTAL_DAYS = business days` (exclude Sat/Sun and `HOLIDAYS` where `ACTIVE_FLAG='Y'` and `LOCATION_CODE IS NULL OR = employee location`); half-day -> 0.5 (only valid when start = end - tighten); no overlap with `PENDING|APPROVED` requests (`START <= other.END AND END >= other.START`); for `ACCRUAL_FLAG='Y'` types, `AVAILABLE >= TOTAL_DAYS` where `AVAILABLE = OPENING + ACCRUED - USED + ADJUSTMENT - PENDING`; status `PENDING` unless `REQUIRES_APPROVAL='N'` -> `APPROVED`; approver = employee's manager; add to `PENDING` balance; notify manager; audit.
- Approve: `PKG_LEAVE.approve_leave_request` does not check who approves; the form relies on the queue being filtered to the manager. Target rule: approver must be the request's `APPROVER_EMP_ID` (manager of record) or hold `HR_ADMIN`. Only from `PENDING`; `PENDING -= days`, `USED += days`; notify employee. Reject: release `PENDING`, reason mandatory. Cancel: from `PENDING` (release pending) or `APPROVED` (release used); only owner (or HR).
- Balance year = `EXTRACT(YEAR FROM START_DATE)`; balances auto-initialised on first accrual/submit.
- Accrual (monthly): active employees x `ACCRUAL_FLAG='Y' AND ACCRUAL_FREQUENCY='MONTHLY'` types; skip if tenure < min; cap `ACCRUED` so `AVAILABLE <= MAX_BALANCE`; log to `LEAVE_ACCRUAL_LOG`; **must be idempotent per (emp, type, month)**.
- Carryover: `MIN(remaining, CARRYOVER_MAX)` into next year's `CARRYOVER_FROM_PREV`, expiry = `CARRYOVER_EXPIRY` months; expire once (fix double-subtract).
- Error codes `-20201..-20204` map to `LEAVE_INSUFFICIENT_BALANCE`, `LEAVE_OVERLAP`, `LEAVE_INVALID_TYPE`, `LEAVE_APPROVAL_ERROR`.

Data access to convert:
- Default `WHERE EMP_ID = :GLOBAL.current_emp_id` on `LEAVE_REQUEST` block -> `GET /api/leave/requests?mine=true`, filter applied server-side from principal (never from a query parameter).
- `LEAVE_BALANCE` block joined to `LEAVE_TYPES` -> `GET /api/leave/balances?year=`; calculated `AVAILABLE` returned by API.
- `PENDING_APPROVAL` block (query on requests where approver = current user) -> `GET /api/leave/approvals` using `VW_PENDING_APPROVALS` semantics.
- `TEAM_CAL` block -> `GET /api/leave/team-calendar?from=&to=` (port of `get_team_calendar` ref cursor: direct reports' approved/pending leave).
- `PKG_LEAVE.calculate_business_days` (day-by-day loop with a `SELECT COUNT(*)` per day) -> Java `BusinessDayCalculator` with holidays pre-loaded per year/location.
- `DBMS_SCHEDULER` accrual -> `@Scheduled(cron)` with ShedLock / DB lock so only one node runs it; chunked transactions per employee (replacing commit-every-100).

UI elements to recreate:
- 4 tabs -> 4 routes: My Requests (table with status chips + Cancel action), Submit (leave-type select, date range picker, half-day toggle, reason textarea, live "Days: n / Available: m" preview via `GET /api/leave/quote`), Approvals (queue with Approve/Reject + reason), Team Calendar (month grid).
- Balance card per leave type (Opening/Accrued/Used/Pending/Available).
- LOV `LOV_LEAVE_TYPES` -> select fed by `/api/lookups/leave-types`.

---

## 5. `HRMS_PAYROLL`

| | |
|---|---|
| Source | `forms/xml-exports/HRMS_PAYROLL.xml`; `plsql/packages/PKG_PAYROLL.pks/.pkb`; `schema/tables/02_payroll_tables.sql`; `VW_PAYROLL_LATEST`; `trg_audit.sql` (`TRG_SALARY_AUDIT`); `PKG_INTEGRATION.generate_gl_journal` |
| Target (web) | `hrms-web/src/app/payroll/pay-periods.component.ts`, `payroll-runs.component.ts`, `run-detail.component.ts` (per-employee details, error rows), `payslip.component.ts`, `pay-register-download.component.ts` |
| Target (api) | `payroll/api/PayPeriodController.java`, `PayrollRunController.java` (`POST /api/payroll/runs`, `POST .../{id}:calculate` (async, returns 202 + job id), `POST .../{id}:approve`, `POST .../{id}:reverse`, `GET .../{id}/details`), `PayslipController.java`, `PayRegisterController.java` (streams CSV); Phase 4: `payroll/service/PayrollFacade.java` (`SimpleJdbcCall` -> `PKG_PAYROLL`); Phase 6: `payroll/domain/TaxEngine.java`, `FederalTaxCalculator`, `StateTaxCalculator`, `FicaCalculator`, `MedicareCalculator`, `PayrollCalculationService.java`; `payroll/shadow/ShadowRunComparator.java` |
| Target (db) | `db/V4__tax_brackets_seed.sql` (2024 brackets/rates as data), `db/V5__pkg_payroll_txn.sql` (restartable calc, no mid-run commits) |

Business rules to preserve:
- Pay period generation: `MONTHLY` (1st..last day), `BIWEEKLY` (14-day periods aligned so the first period of the year ends on a Friday), `WEEKLY`; `PERIOD_NAME` like `2024-BW-01`; status `OPEN -> CLOSED`; `PAY_DATE` = period end + 5 days.
- Run lifecycle: `PENDING -> CALCULATING -> CALCULATED | ERROR -> APPROVED`; `reverse_payroll` sets run and details to `REVERSED`; approve only from `CALCULATED` (`-20103`); approve requires `PAYROLL/APPROVE` (enforce in API, currently form-only).
- Per-employee calc: annual salary as of period end (`get_salary_as_of`); `periods_per_year` 12/26/52; `GROSS = ROUND(annual/periods, 2)`; earnings row `BASE_PAY`; taxes from `EMPLOYEE_TAX_INFO` (defaults `SINGLE`, 0 allowances, 0 extra withholding); YTD earnings by tax year feed SS wage-base and additional-Medicare thresholds; deductions from `EMPLOYEE_PAY_ELEMENTS` effective in period ordered by `PRIORITY_ORDER`, `FLAT` or `PERCENTAGE` of gross; `NET = GROSS - TOTAL_TAX - TOTAL_DEDUCTIONS`; all amounts `ROUND(...,2)`.
- Tax constants (2024, to become data): SS 6.2% to 168,600 wage base; Medicare 1.45% + 0.9% above 200,000; federal tax = annualise period taxable (`x periods`), subtract standard deduction (14,600 single / 29,200 `MARRIED_JOINT`) and allowances x 4,300, apply brackets for `SINGLE|MARRIED_SEPARATE` or `MARRIED_JOINT`, divide back by periods, `ROUND(,2)`, add additional withholding. **Any other filing status (e.g. `HEAD_OF_HOUSEHOLD`) falls through with zero federal tax** - legacy defect to decide on. State flat rates per code with a default for unknown states.
- Error isolation: a failing employee produces a `PAYROLL_DETAILS` row with `ELEMENT_TYPE='ERROR'` and the run continues; run ends `ERROR` if any failures - keep the reporting semantics but make the run atomic or resumable.
- Pay register: CSV `EMP_NUMBER, NAME, GROSS, TAX, DEDUCTIONS, NET` for `CALCULATED|APPROVED|PAID` details.
- Salary record changes audited via `TRG_SALARY_AUDIT` (keep trigger).

Data access to convert:
- `PAY_PERIOD` block default query `STATUS='OPEN'` -> `GET /api/payroll/periods?status=OPEN`.
- `PAYROLL_RUN` master / `PAYROLL_DETAIL` detail relation -> `GET /api/payroll/runs?periodId=` and `GET /api/payroll/runs/{id}/details?page=`.
- Synchronous `PKG_PAYROLL.calculate_payroll` with `SYNCHRONIZE` wait message -> async job (`@Async` or job table) polled by UI; run status drives buttons.
- `UTL_FILE` to `PAYROLL_OUTPUT` -> API streams CSV (`StreamingResponseBody`); optional drop to object storage/SFTP by integration module.
- `get_payslip` / `get_ytd_earnings` ref cursors -> `PayslipDto` (fix YTD placeholders by computing from `get_ytd_earnings`).
- `FOR UPDATE` on `PAYROLL_RUNS` in approve/calculate -> `SELECT ... FOR UPDATE` in facade transaction (keep) or optimistic version.

UI elements to recreate:
- Tabs Pay Periods / Payroll Runs / Pay Details -> routes; buttons Create Run, Calculate, Approve, Reverse, Download Register, all permission-gated and status-gated (`PENDING` only for Calculate, `CALCULATED` only for Approve).
- Progress indicator for async calculation with employee/error counts.
- Detail grid grouped by employee with `ERROR` rows highlighted; payslip view with YTD columns.

---

## 6. `HRMS_PERFORMANCE`

| | |
|---|---|
| Source | `forms/xml-exports/HRMS_PERFORMANCE.xml`; `plsql/packages/PKG_PERFORMANCE.pks/.pkb`; `schema/tables/04_performance_tables.sql` |
| Target (web) | `hrms-web/src/app/performance/review-cycles.component.ts`, `my-reviews.component.ts`, `review-detail.component.ts` (self-assessment / manager review / acknowledgement steps), `goals.component.ts`, `team-reviews.component.ts`, `rating-distribution.component.ts` |
| Target (api) | `performance/api/ReviewCycleController.java` (`POST /api/performance/cycles`, `:open`, `:close`, `:generate-reviews`), `ReviewController.java` (`GET /api/performance/reviews?mine`, `POST .../{id}/self-assessment`, `POST .../{id}/manager-review`, `POST .../{id}:acknowledge`), `GoalController.java`, `CalibrationController.java` (`GET .../cycles/{id}/rating-distribution`); `performance/domain/{ReviewCycle,PerformanceReview,Goal,RatingLabel}.java`; `performance/service/*Service.java`; `performance/persistence/*Repository.java` |

Business rules to preserve:
- Cycle: created `DRAFT`; `open` -> `OPEN`; `close` -> `CLOSED` (add guard: only from `OPEN`, and optionally require all reviews `COMPLETED|ACKNOWLEDGED`).
- Review: created `NOT_STARTED` with reviewer = employee's manager; one review per (cycle, employee) - duplicates ignored today via `DUP_VAL_ON_INDEX`; expose as 409.
- Self-assessment allowed from `NOT_STARTED|SELF_REVIEW` -> `MANAGER_REVIEW`; notifies reviewer.
- Manager review: rating 1.0-5.0 (`-20403`), label thresholds 4.5/3.5/2.5/1.5; requires `MANAGER_ASSESSMENT`, optional `DEVELOPMENT_PLAN`; -> `COMPLETED`; notifies employee. Add guard: from `MANAGER_REVIEW` only, by the assigned reviewer.
- Acknowledge: from `COMPLETED` -> `ACKNOWLEDGED`, sets `EMPLOYEE_ACK_DATE`, optional comments; only the employee.
- Goals: `CATEGORY IN (BUSINESS, DEVELOPMENT, LEADERSHIP, INNOVATION, COMPLIANCE)`, weight %, `PROGRESS_PCT` 0-100 -> status `NOT_STARTED / IN_PROGRESS / COMPLETED` unless explicit status given.
- Rating distribution: count and percentage per label for a cycle (`COUNT(*)*100/SUM(COUNT(*)) OVER ()`), only `COMPLETED|ACKNOWLEDGED`.

Data access to convert:
- `REVIEW_CYCLE` block with direct DML -> cycle endpoints (no direct DML).
- `PERFORMANCE_REVIEW` block queried by `EMP_ID = :GLOBAL.current_emp_id OR REVIEWER_EMP_ID = :GLOBAL.current_emp_id` -> `GET /api/performance/reviews?role=employee|reviewer` from principal.
- `CLOB` assessments -> `String` columns via Spring Data JDBC (`@Column` on `CLOB`), size-limited in DTO validation.
- `get_team_reviews`, `get_rating_distribution` ref cursors -> read repositories.

UI elements to recreate:
- Tabs Review Cycles (admin), My Reviews, Goals -> routes; stepper for the review workflow (Self -> Manager -> Acknowledge) with role-aware read-only states.
- Rating input (1.0-5.0 with 0.5 step) showing derived label live; rich textarea for CLOB fields with autosave-draft (optional, new).
- Goals table with progress bar and category chips; calibration chart (bar per label).

---

## 7. Shared libraries and cross-cutting packages

| Source | Target | Notes |
|---|---|---|
| `forms/libraries/HRMS_COMMON_LIB.pll.sql` (`handle_error`, toolbar procs, `format_date_display`, `get_current_user_emp_id`, `check_session`, `refresh_lov`) | `hrms-api/.../common/{HrmsException,ApiExceptionHandler}.java`; `hrms-web/src/app/core/{error.interceptor.ts, date.pipe.ts, lookup.service.ts}` | Toolbar procedures have no equivalent - per-page actions. `DD-MON-YYYY` display format -> Angular `DatePipe` with org locale. |
| `forms/libraries/HRMS_VALIDATION_LIB.pll.sql` | `hrms-web/src/app/core/validators.ts` (client) **generated from** / kept in sync with `hrms-api/.../common/Validation.java` (server); server is authoritative | Fix the noted email-regex drift; salary range validated server-side only (needs DB). |
| `PKG_COMMON` (`get_param*`, `business_days_between`, `add_business_days`, `get_fiscal_year/quarter` (FY starts Oct), `format_*`, `is_valid_*`) | `common/SystemParameterService.java`, `common/FiscalCalendar.java`, `common/Formatters.java`, `common/Validation.java` | `format_ssn_masked` -> `Masking.ssn()` used by every DTO that touches SSN. |
| `PKG_AUDIT` (`log_action`, `get_change_history`, `purge_old_records`) | `audit/AuditService.java` (`REQUIRES_NEW`), `audit/AuditController.java` (`GET /api/audit/{table}/{id}`), scheduled purge | Keep `AUDIT_LOG` table and DB triggers (`trg_audit.sql`) so Forms-side writes are still audited during coexistence. |
| `PKG_NOTIFICATION` (`send_notification`, `process_queue` via `UTL_SMTP`, `retry_failed`) | `notification/NotificationOutbox.java` + `NotificationDispatcher.java` (Spring Mail / JavaMail to relay), keep `NOTIFICATIONS` table as the outbox | Read SMTP host from config/Vault, not the package constant. |
| `PKG_VALIDATION` | `common/Validation.java` + Bean Validation annotations on request DTOs | |
| `PKG_REPORTING`, `PKG_INTEGRATION`, `HRMS_REPORTS`/`HRMS_ADMIN` (*not in repo*) | `reporting/*` read models over `VW_*`; `integration/{GlJournalExporter,BenefitsFeedExporter,TimeAttendanceImporter}.java` writing to storage/SFTP; admin pages for `SYSTEM_PARAMETERS`, `HOLIDAYS`, `LEAVE_TYPES`, `PAY_ELEMENTS`, `TAX_BRACKETS`, roles | Phase 5. Remove FTP credentials from `SYSTEM_PARAMETERS` into Vault. |
| `schema/views/hrms_views.sql` | Reused as-is by read repositories (`VW_ACTIVE_EMPLOYEES`, `VW_ORG_HIERARCHY`, `VW_EMPLOYEE_COMPENSATION`, `VW_LEAVE_SUMMARY`, `VW_PAYROLL_LATEST`, `VW_PENDING_APPROVALS`) | Consider materialising `VW_ORG_HIERARCHY` if the `CONNECT BY` cost is confirmed. |

## 8. Error code mapping (PL/SQL -> API)

| PL/SQL | API code | HTTP |
|---|---|---|
| `-20001` employee not found | `EMPLOYEE_NOT_FOUND` | 404 |
| `-20002` duplicate employee number | `EMPLOYEE_NUMBER_DUPLICATE` | 409 |
| `-20003` invalid department | `DEPARTMENT_INVALID` | 422 |
| `-20004` invalid manager / cycle | `MANAGER_INVALID` | 422 |
| `-20005` termination error | `TERMINATION_INVALID` | 422 |
| `-20101` salary must be positive, `-20102` period closed, `-20103` approve from wrong status, `-20104` run/period error | `SALARY_INVALID`, `PERIOD_CLOSED`, `RUN_STATUS_INVALID`, `PAYROLL_RUN_ERROR` | 422/409 |
| `-20201` insufficient balance | `LEAVE_INSUFFICIENT_BALANCE` | 422 |
| `-20202` overlap | `LEAVE_OVERLAP` | 409 |
| `-20203` invalid leave type | `LEAVE_INVALID_TYPE` | 422 |
| `-20204` approval error | `LEAVE_APPROVAL_ERROR` | 409 |
| `-20301` invalid credentials | handled by IdP | 401 |
| `-20401` cycle not `DRAFT`, `-20402` review not found / wrong status, `-20403` rating out of range | `CYCLE_STATUS_INVALID`, `REVIEW_STATUS_INVALID`, `RATING_OUT_OF_RANGE` | 409/404/422 |
| `-20501..-20504` employee triggers | `HIRE_DATE_TOO_FAR`, `EMAIL_IN_USE`, `REACTIVATION_FORBIDDEN`, `DELETE_FORBIDDEN` | 422/409 |
| `ORA-00001` (`DUP_VAL_ON_INDEX`) | `CONFLICT` | 409 |
| `ORA-00054` / `FRM-40501` lock | `RECORD_LOCKED` | 409 |
