# HRMS Application Inventory

Inventory of every source artifact physically present in this repository, derived from reading the files (not from the README alone). Dependencies listed are those directly referenced in the artifact's own source. Where the README names an artifact that is **not** in the repository, it is listed separately in [Section 8](#8-artifacts-referenced-but-not-present-in-the-repository).

Companion documents: [DEPENDENCY_MAP.md](DEPENDENCY_MAP.md), [DATA_DICTIONARY.md](DATA_DICTIONARY.md), [TECH_DEBT_REGISTRY.md](TECH_DEBT_REGISTRY.md).

> **Target stack note.** Everything inventoried here is the *legacy* system: Oracle Forms 12c on WebLogic and PL/SQL on Oracle Database 19c. The modernization target ([MODERNIZATION_BLUEPRINT.md](MODERNIZATION_BLUEPRINT.md)) is Spring Boot + React on **PostgreSQL**; none of the Forms modules, `.pll` libraries, `PKG_*` packages or triggers below are carried into the target. The legacy schema remains on Oracle only during coexistence, where the packages serve as the characterization / golden oracle until each module is validated and then dropped ([CUTOVER_PLAN.md](CUTOVER_PLAN.md) §2, [TEST_STRATEGY.md](TEST_STRATEGY.md) §3).

## Summary

| Layer | Type | Count | Files |
|---|---|---|---|
| Presentation | Oracle Forms module (XML export) | 6 | `forms/xml-exports/*.xml` |
| Presentation | Oracle Forms menu module | 1 | `forms/menus/HRMS_MENU.mmb.sql` |
| Presentation | Forms PL/SQL library (PLL) | 2 | `forms/libraries/*.pll.sql` |
| Business logic | PL/SQL package (spec + body) | 11 | `plsql/packages/PKG_*.pks/.pkb` (22 files, 5,169 lines) |
| Business logic | Database trigger script | 2 (6 triggers) | `plsql/triggers/*.sql` |
| Data | Table DDL | 4 scripts (30 tables) | `schema/tables/0*_*.sql` |
| Data | Sequence DDL | 1 script (29 sequences) | `schema/sequences/hrms_sequences.sql` |
| Data | View DDL | 1 script (6 views) | `schema/views/hrms_views.sql` |
| Data | Seed / reference data | 2 scripts | `data/seed/0*_*.sql` |
| Docs | README, .gitignore | 2 | `README.md`, `.gitignore` |

Type legend used below: **Frontend** (Oracle Forms module), **Menu**, **Library** (PLL attached to forms), **PL/SQL** (database package), **Trigger**, **Schema** (DDL), **Seed data**.

---

## 1. Oracle Forms modules (`forms/xml-exports/`)

All six are Forms Builder 12c XML exports of `.fmb` binaries (the binaries themselves are not in the repo).

| File | Type | Purpose | Blocks / base tables | Attached PLL | PL/SQL packages called | Other DB dependencies |
|---|---|---|---|---|---|---|
| `HRMS_LOGIN.xml` | Frontend | Login screen; authenticates the user, stores `:GLOBAL.session_id`, `:GLOBAL.current_user`, `:GLOBAL.current_emp_id`, then `OPEN_FORM('HRMS_MENU')`. Header comment lists known issues: cleartext password, no lockout, no CAPTCHA/2FA. | `LOGIN` (control block, no base table) | none | `PKG_SECURITY.authenticate` | `EMPLOYEES` (lookup of `EMP_ID` by email after login) |
| `HRMS_MENU.xml` | Frontend | Main-menu shell (button canvas + pull-down menu). Checks permissions per module and opens child forms with `OPEN_FORM(..., ACTIVATE, SESSION)`. Logs out via package. | `MENU_CONTROL` (control block) | `HRMS_COMMON_LIB` | `PKG_SECURITY.has_permission`, `PKG_SECURITY.logout` | Opens `HRMS_EMPLOYEE`, `HRMS_PAYROLL`, `HRMS_LEAVE`, `HRMS_PERFORMANCE` (and `HRMS_REPORTS`/`HRMS_ADMIN`, which are not in the repo) |
| `HRMS_EMPLOYEE.xml` | Frontend | Employee maintenance (master/detail employee + salary). Session check, permission-based read-only mode, `DEFAULT_WHERE` on active employees, LOVs, client-side validation, `EMP_NUMBER` generation in `PRE-INSERT`, 90-day future hire-date rule in `WHEN-VALIDATE-ITEM`. | `EMPLOYEE` → `HRMS.EMPLOYEES`; `SALARY` → `HRMS.SALARY_RECORDS`; relation `EMP_SALARY_REL` | `HRMS_COMMON_LIB`, `HRMS_VALIDATION_LIB` | `PKG_SECURITY.is_session_valid`, `PKG_SECURITY.has_permission`, `PKG_EMPLOYEE.generate_emp_number`, `PKG_VALIDATION.validate_email_format` | Record groups `RG_DEPARTMENTS`, `RG_JOB_TITLES`, `RG_MANAGERS`, `RG_LOCATIONS` query `DEPARTMENTS`, `JOB_TITLES`, `EMPLOYEES`, `LOCATIONS`; `SEQ_EMPLOYEE` |
| `HRMS_LEAVE.xml` | Frontend | Employee self-service leave: view balances and requests (filtered by `:GLOBAL.current_emp_id` via `DEFAULT_WHERE`), submit new request, cancel request. | `LEAVE_REQUEST` → `HRMS.LEAVE_REQUESTS`; `LEAVE_BALANCE` → `HRMS.LEAVE_BALANCES`; `NEW_REQUEST` (control) | `HRMS_COMMON_LIB` | `PKG_SECURITY.is_session_valid`, `PKG_LEAVE.submit_leave_request`, `PKG_LEAVE.cancel_leave_request` | `RG_LEAVE_TYPES` → `LEAVE_TYPES` |
| `HRMS_PAYROLL.xml` | Frontend | Payroll operator screen: lists open pay periods and their runs; buttons to create, calculate and approve a payroll run. Permission-gated. | `PAY_PERIOD` → `HRMS.PAY_PERIODS`; `PAYROLL_RUN` → `HRMS.PAYROLL_RUNS`; relation `PERIOD_RUN_REL` | `HRMS_COMMON_LIB` | `PKG_SECURITY.is_session_valid`, `PKG_SECURITY.has_permission`, `PKG_PAYROLL.create_payroll_run`, `PKG_PAYROLL.calculate_payroll`, `PKG_PAYROLL.approve_payroll` | — |
| `HRMS_PERFORMANCE.xml` | Frontend | Performance-review browser: cycle → reviews → goals master/detail. Header comment claims 4 blocks (`REVIEW_DETAIL`) but only 3 are defined in the export. | `REVIEW_CYCLE` → `HRMS.REVIEW_CYCLES`; `PERFORMANCE_REVIEW` → `HRMS.PERFORMANCE_REVIEWS`; `PERFORMANCE_GOAL` → `HRMS.PERFORMANCE_GOALS`; relations `CYCLE_REVIEW_REL`, `REVIEW_GOAL_REL` | `HRMS_COMMON_LIB` | `PKG_SECURITY.is_session_valid` | `EMPLOYEES` (POST-QUERY name lookup) |

## 2. Menu module (`forms/menus/`)

| File | Type | Purpose | Dependencies |
|---|---|---|---|
| `HRMS_MENU.mmb.sql` | Menu | Textual representation of the `.mmb` menu module: File / Employees / Payroll / Leave / Performance / Reports / Admin / Help trees. Menu items call `OPEN_FORM` for each module; visibility is driven by `PKG_SECURITY.has_permission`; Exit calls `PKG_SECURITY.logout`. | `PKG_SECURITY.has_permission`, `PKG_SECURITY.logout`; opens `HRMS_EMPLOYEE`, `HRMS_PAYROLL`, `HRMS_LEAVE`, `HRMS_PERFORMANCE`, `HRMS_REPORTS`*, `HRMS_ADMIN`* (*not in repo) |

## 3. Forms PL/SQL libraries (`forms/libraries/`)

| File | Type | Purpose | Public units | Dependencies |
|---|---|---|---|---|
| `HRMS_COMMON_LIB.pll.sql` | Library | Shared client-side helpers: standard error handler, toolbar actions (save/clear/query/navigate/insert/delete/exit), date formatting, `:GLOBAL` accessors, session check, LOV refresh. Attached by `HRMS_MENU`, `HRMS_EMPLOYEE`, `HRMS_LEAVE`, `HRMS_PAYROLL`, `HRMS_PERFORMANCE`. | `handle_error`, `toolbar_save/clear/query/first/prev/next/last/insert/delete/exit`, `format_date`, `format_datetime`, `get_current_user`, `get_session_id`, `check_session`, `refresh_lov` | `PKG_COMMON.log_error`, `PKG_SECURITY.is_session_valid`; `:GLOBAL.current_user`, `:GLOBAL.session_id`; Forms built-ins (`COMMIT_FORM`, `CLEAR_FORM`, `EXIT_FORM`, `POPULATE_GROUP`, …) |
| `HRMS_VALIDATION_LIB.pll.sql` | Library | Client-side field validation that duplicates server-side `PKG_VALIDATION`/`PKG_COMMON` rules (header comment acknowledges drift). Attached by `HRMS_EMPLOYEE` only. | `validate_email`, `validate_phone`, `validate_ssn`, `validate_date_not_future`, `validate_salary_range` | Direct SQL against `JOB_GRADES` (`validate_salary_range`); no package calls |

## 4. PL/SQL packages (`plsql/packages/`)

Each package has a spec (`.pks`) and body (`.pkb`), schema `HRMS`. "Declared" dependencies are from the header comment in the spec; "Actual" are calls found in the body. Line counts are spec + body.

| Package | Lines | Type | Purpose | Declared package deps (spec comment) | Actual package calls (body) | Tables / sequences touched | Oracle built-ins |
|---|---|---|---|---|---|---|---|
| `PKG_COMMON` | 121 + 283 | PL/SQL (utility) | Error/info logging (autonomous), `SYSTEM_PARAMETERS` get/set, business-day math, fiscal year/quarter (Oct-1 start), formatting (phone, masked SSN, currency, name), email/phone/SSN validation. | none (base package) | none | `AUDIT_LOG`, `SYSTEM_PARAMETERS`; `SEQ_AUDIT` | `DBMS_OUTPUT` |
| `PKG_AUDIT` | 32 + 72 | PL/SQL (utility) | Central audit trail: `log_action` (autonomous, swallows all errors), `purge_old_records`, `get_change_history`. | none (base package) | none | `AUDIT_LOG`; `SEQ_AUDIT` | `SYS_CONTEXT`, `DBMS_OUTPUT` |
| `PKG_VALIDATION` | 47 + 125 | PL/SQL (utility) | Server-side validation façade: date range, salary-vs-grade, email/phone/emp-number format, business day, required fields for `EMPLOYEES`. | `PKG_COMMON` | `PKG_COMMON.is_valid_email`, `PKG_COMMON.is_valid_phone` | `JOB_GRADES`, `HOLIDAYS`, `EMPLOYEES` | — |
| `PKG_NOTIFICATION` | 42 + 177 | PL/SQL (service) | Async notification queue: enqueue (autonomous), SMTP delivery of queue (`process_queue`), retry, cancel. SMTP host/port/from hard-coded in body. | `PKG_COMMON` | `PKG_COMMON.log_error`, `PKG_COMMON.log_info` | `NOTIFICATION_QUEUE`, `EMPLOYEES`; `SEQ_NOTIFICATION` | `UTL_SMTP`, `UTL_TCP`; intended to be run by `DBMS_SCHEDULER` |
| `PKG_SECURITY` | 63 + 237 | PL/SQL (service) | Authentication (`authenticate`), session lifecycle (`logout`, `is_session_valid`, 30-min timeout), grade-based `has_permission`, SSN AES-256 encrypt/decrypt, MD5 `hash_password`, `change_password` stub. | `PKG_COMMON`, `PKG_AUDIT` | `PKG_AUDIT.log_action`, **`PKG_EMPLOYEE.set_session_context`** (undeclared) | `EMPLOYEES`, `JOB_TITLES`, `USER_SESSIONS`; `SEQ_USER_SESSION` | `DBMS_CRYPTO`, `UTL_RAW` |
| `PKG_EMPLOYEE` | 192 + 966 | PL/SQL (domain) | Employee lifecycle: create/update/get/search, transfer, promote, terminate, rehire, org chart, `is_active`, `generate_emp_number` (MAX+1), `set_session_context`, private `log_history`. | `PKG_COMMON`, `PKG_AUDIT`, `PKG_NOTIFICATION`, `PKG_PAYROLL` | `PKG_COMMON.log_error`, `PKG_AUDIT.log_action`, `PKG_NOTIFICATION.send_notification`, `PKG_PAYROLL.create_salary_record` | `EMPLOYEES`, `EMPLOYEE_HISTORY`, `DEPARTMENTS`, `JOB_TITLES`, `JOB_GRADES`, `SALARY_RECORDS`, `EMPLOYEE_PAY_ELEMENTS`, `LEAVE_REQUESTS`; `SEQ_EMPLOYEE`, `SEQ_EMP_HISTORY` | `DBMS_OUTPUT`, native dynamic SQL (`OPEN … FOR v_sql`) |
| `PKG_PAYROLL` | 164 + 897 | PL/SQL (domain) | Salary records, pay periods, payroll runs (`create_payroll_run`, `calculate_payroll` row-by-row, `approve_payroll`, `reverse_payroll`), tax engine (federal brackets/FICA/Medicare/state hard-coded for 2024), deductions, payslip, YTD, flat-file pay register. | `PKG_EMPLOYEE`, `PKG_COMMON`, `PKG_AUDIT`, `PKG_NOTIFICATION` | `PKG_COMMON.log_error`, `PKG_AUDIT.log_action` (no direct `PKG_EMPLOYEE`/`PKG_NOTIFICATION` call found in body) | `SALARY_RECORDS`, `PAY_PERIODS`, `PAYROLL_RUNS`, `PAYROLL_DETAILS`, `PAY_ELEMENTS`, `EMPLOYEE_PAY_ELEMENTS`, `EMPLOYEE_TAX_INFO`, `EMPLOYEES`, `DEPARTMENTS` (`TAX_BRACKETS` is mentioned only in TODO comments, never read); `SEQ_SALARY`, `SEQ_PAY_PERIOD`, `SEQ_PAYROLL_RUN`, `SEQ_PAYROLL_DETAIL` | `UTL_FILE` (directory `PAYROLL_OUTPUT`), `DBMS_OUTPUT` |
| `PKG_LEAVE` | 128 + 673 | PL/SQL (domain) | Leave requests (submit/approve/reject/cancel), balances, `initialize_balances`, monthly accrual batch, year-end carryover, carryover expiry, pending-request and team-calendar cursors, business-day calc with holidays, overlap check. | `PKG_EMPLOYEE`, `PKG_COMMON`, `PKG_AUDIT`, `PKG_NOTIFICATION` | `PKG_AUDIT.log_action`, `PKG_NOTIFICATION.send_notification` (no direct `PKG_EMPLOYEE`/`PKG_COMMON` call found) | `LEAVE_REQUESTS`, `LEAVE_BALANCES`, `LEAVE_TYPES`, `LEAVE_ACCRUAL_LOG`, `HOLIDAYS`, `EMPLOYEES`; `SEQ_LEAVE_REQUEST`, `SEQ_LEAVE_BALANCE`, `SEQ_LEAVE_ACCRUAL` | `DBMS_OUTPUT`; intended to be run by `DBMS_SCHEDULER` |
| `PKG_PERFORMANCE` | 97 + 320 | PL/SQL (domain) | Review cycles (create/open/close), reviews (create, self-assessment, manager review with rating label, acknowledge), goals (add, progress), team reviews, rating distribution, bulk review generation. | `PKG_EMPLOYEE`, `PKG_COMMON`, `PKG_AUDIT`, `PKG_NOTIFICATION` | `PKG_AUDIT.log_action`, `PKG_NOTIFICATION.send_notification` | `REVIEW_CYCLES`, `PERFORMANCE_REVIEWS`, `PERFORMANCE_GOALS`, `EMPLOYEES`, `JOB_TITLES`, `DEPARTMENTS`; `SEQ_REVIEW_CYCLE`, `SEQ_PERF_REVIEW`, `SEQ_PERF_GOAL` | `DBMS_OUTPUT` |
| `PKG_REPORTING` | 63 + 207 | PL/SQL (reporting) | Ref-cursor reports: headcount, compensation summary (compa-ratio), turnover, new hires, leave utilization, payroll summary, EEO compliance; `refresh_reporting_tables` placeholder. | `PKG_EMPLOYEE`, `PKG_PAYROLL`, `PKG_COMMON` | `PKG_COMMON.log_info` only | `EMPLOYEES`, `DEPARTMENTS`, `LOCATIONS`, `JOB_TITLES`, `JOB_GRADES`, `SALARY_RECORDS`, `LEAVE_BALANCES`, `LEAVE_TYPES`, `PAYROLL_DETAILS`, `PAYROLL_RUNS` (read-only) | — |
| `PKG_INTEGRATION` | 50 + 213 | PL/SQL (integration) | Flat-file integrations: GL journal export (pipe-delimited), ADP benefits feed (fixed-width), time & attendance CSV import (parsing is a TODO), `sync_org_structure` placeholder, `get_integration_status`. | `PKG_COMMON`, `PKG_PAYROLL`, `PKG_EMPLOYEE` | `PKG_COMMON.log_info`, `PKG_COMMON.log_error`, `PKG_COMMON.get_param` only | `PAYROLL_DETAILS`, `PAYROLL_RUNS`, `PAY_PERIODS`, `PAY_ELEMENTS`, `EMPLOYEES`, `DEPARTMENTS`, `EMPLOYEE_DEPENDENTS`, `SYSTEM_PARAMETERS` (read) | `UTL_FILE` (directories `GL_FEED_OUT`, `BENEFITS_FEED_OUT`, `TIME_ATTENDANCE_IN`) |

## 5. Database triggers (`plsql/triggers/`)

| File | Trigger | Table / event | Purpose | Dependencies |
|---|---|---|---|---|
| `trg_employees.sql` | `TRG_EMP_BEFORE_INSERT` | `EMPLOYEES` BEFORE INSERT ROW | Defaults audit columns, `ACTIVE_FLAG`, `EMPLOYMENT_STATUS`; rejects hire date > 180 days ahead; case-insensitive email uniqueness check via `SELECT COUNT(*) FROM EMPLOYEES`. | `EMPLOYEES` (self-query) |
| `trg_employees.sql` | `TRG_EMP_BEFORE_UPDATE` | `EMPLOYEES` BEFORE UPDATE ROW | Sets `MODIFIED_*`; blocks direct TERMINATED→ACTIVE; writes `EMPLOYEE_HISTORY` rows on status/dept/job change (using column names that do not exist in the DDL – see tech-debt registry). | `EMPLOYEE_HISTORY`, `SEQ_EMP_HISTORY` |
| `trg_employees.sql` | `TRG_EMP_INSTEAD_OF_DELETE` | `EMPLOYEES` BEFORE DELETE ROW | Raises `-20504` to forbid physical delete (soft-delete policy). | — |
| `trg_audit.sql` | `TRG_SALARY_AUDIT` | `SALARY_RECORDS` AFTER I/U/D ROW | Builds JSON snapshots and calls audit package. | `PKG_AUDIT.log_action` |
| `trg_audit.sql` | `TRG_LEAVE_REQUEST_AUDIT` | `LEAVE_REQUESTS` AFTER UPDATE OF STATUS ROW | Audits status transitions (passes action `'STATUS_CHANGE'`). | `PKG_AUDIT.log_action` |
| `trg_audit.sql` | `TRG_DEPARTMENT_AUDIT` | `DEPARTMENTS` AFTER I/U/D ROW | Audits department changes. | `PKG_AUDIT.log_action` |

Note: the README describes `trg_employees.sql` as calling `PKG_EMPLOYEE.rehire_employee`; the trigger only *mentions* it in a comment/error message and does not call it.

## 6. Schema DDL (`schema/`)

| File | Type | Objects | Notes |
|---|---|---|---|
| `schema/tables/01_core_tables.sql` | Schema | `DEPARTMENTS`, `LOCATIONS`, `JOB_GRADES`, `JOB_TITLES`, `EMPLOYEES`, `EMPLOYEE_HISTORY`, `EMPLOYEE_DEPENDENTS`, `EMERGENCY_CONTACTS` | Organisation and employee master data; FKs `EMPLOYEES→DEPARTMENTS/JOB_TITLES/LOCATIONS/EMPLOYEES(manager)`. |
| `schema/tables/02_payroll_tables.sql` | Schema | `SALARY_RECORDS`, `PAY_ELEMENTS`, `EMPLOYEE_PAY_ELEMENTS`, `PAY_PERIODS`, `PAYROLL_RUNS`, `PAYROLL_DETAILS`, `TAX_BRACKETS`, `EMPLOYEE_TAX_INFO`, `EMPLOYEE_BANK_ACCOUNTS` | Compensation and payroll processing. |
| `schema/tables/03_leave_tables.sql` | Schema | `LEAVE_TYPES`, `LEAVE_BALANCES`, `LEAVE_REQUESTS`, `LEAVE_ACCRUAL_LOG`, `HOLIDAYS` | Leave management; `LEAVE_BALANCES.AVAILABLE` is a virtual column. |
| `schema/tables/04_performance_tables.sql` | Schema | `REVIEW_CYCLES`, `PERFORMANCE_REVIEWS`, `PERFORMANCE_GOALS`, `AUDIT_LOG`, `SYSTEM_PARAMETERS`, `NOTIFICATION_QUEUE`, `USER_SESSIONS`, `LOOKUP_VALUES` | Performance module plus cross-cutting system tables. |
| `schema/sequences/hrms_sequences.sql` | Schema | 29 sequences `SEQ_*` (all `NOCACHE` except `SEQ_AUDIT CACHE 100`) | No sequence exists for `EMPLOYEE_TAX_INFO` or `EMPLOYEE_BANK_ACCOUNTS`; `SEQ_EMP_NUMBER` is defined but never used. |
| `schema/views/hrms_views.sql` | Schema | `VW_ACTIVE_EMPLOYEES`, `VW_ORG_HIERARCHY`, `VW_EMPLOYEE_COMPENSATION`, `VW_LEAVE_SUMMARY`, `VW_PAYROLL_LATEST`, `VW_PENDING_APPROVALS` | Consumed by Oracle Reports / LOVs / external BI per header; none of the in-repo forms or packages reference them. |

Full column-level detail is in [DATA_DICTIONARY.md](DATA_DICTIONARY.md).

## 7. Seed data (`data/seed/`)

| File | Type | Purpose | Tables populated (row counts) | Notes |
|---|---|---|---|---|
| `01_reference_data.sql` | Seed data | Reference/lookup data, run first. | `LOCATIONS` (3), `JOB_GRADES` (10), `DEPARTMENTS` (10), `JOB_TITLES` (26), `LEAVE_TYPES` (6), `PAY_ELEMENTS` (11), `HOLIDAYS` (10), `SYSTEM_PARAMETERS` (10) | Several INSERTs use column names that do not exist in the DDL (`LOCATIONS.PHONE`, `JOB_GRADES.GRADE_LEVEL`, `SYSTEM_PARAMETERS.DESCRIPTION`) and omit `JOB_GRADES.GRADE_CODE NOT NULL` – see tech-debt registry. |
| `02_employee_data.sql` | Seed data | Sample workforce. | `EMPLOYEES` (24), `SALARY_RECORDS` (23) | Column lists match DDL. |

## 8. Artifacts referenced but NOT present in the repository

The README and several source comments refer to artifacts that are not checked in. They are recorded here so that the inventory is not mistaken for a complete system snapshot.

| Referenced artifact | Referenced from | Status |
|---|---|---|
| `HRMS_DEPARTMENT.xml`, `HRMS_REPORTS.xml`, `HRMS_LOV.xml`, `HRMS_TOOLBAR.xml` (forms) | README repository layout | Not in repo |
| `HRMS_REPORTS`, `HRMS_ADMIN` (forms) | `HRMS_MENU.xml`, `HRMS_MENU.mmb.sql` `OPEN_FORM` calls | Not in repo – menu items would fail at runtime |
| `HRMS_TOOLBAR` (object library / form) | `HRMS_COMMON_LIB.pll.sql` toolbar procedures comment | Not in repo |
| `HRMS_REPORT_LIB.pll` | README | Not in repo |
| `plsql/procedures/`, `plsql/functions/`, `plsql/types/` | README | Directories absent |
| `schema/indexes/`, `schema/constraints/` | README | Directories absent – no index DDL exists beyond PK/UK-implied indexes |
| `config/formsweb.cfg`, `config/default.env`, `config/tnsnames.ora`, `docs/` | README | Absent |
| Oracle Reports `.rdf` modules | README, `hrms_views.sql` header | Not in repo |
| `USER_CREDENTIALS` table | `PKG_SECURITY.pkb` comments | No DDL – authentication has no credential store |
| `RPT_*` denormalised reporting tables | `PKG_REPORTING.pkb` comment | No DDL |
| Oracle directory objects `PAYROLL_OUTPUT`, `GL_FEED_OUT`, `BENEFITS_FEED_OUT`, `TIME_ATTENDANCE_IN` | `PKG_PAYROLL.pkb`, `PKG_INTEGRATION.pkb` | No `CREATE DIRECTORY` DDL |
| `DBMS_SCHEDULER` jobs (accrual, notification queue, calibration) | `PKG_LEAVE.pks`, `PKG_NOTIFICATION.pkb`, `PKG_PERFORMANCE.pks` comments | No job DDL |
| Tests of any kind | README ("No unit tests") | None |
