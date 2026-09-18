# HRMS Application Inventory

Baseline inventory of every component checked into this repository, derived from a full read of each
source file (not from the README). Companion documents: `DEPENDENCY_MAP.md`, `DATA_DICTIONARY.md`,
`TECH_DEBT_REGISTRY.md`.

## Reconciliation: README claims vs. repository contents

| Artefact | README claim (`README.md` lines 30-46, 49-92) | Actually in repo | Notes |
|---|---|---|---|
| Oracle Forms modules | 18 forms; directory listing names `HRMS_DEPARTMENT`, `HRMS_REPORTS`, `HRMS_LOV`, `HRMS_TOOLBAR` | **6** XML exports (`HRMS_EMPLOYEE`, `HRMS_PAYROLL`, `HRMS_LEAVE`, `HRMS_PERFORMANCE`, `HRMS_LOGIN`, `HRMS_MENU`) | `HRMS_REPORTS` and `HRMS_ADMIN` are opened by `HRMS_MENU.xml` / `HRMS_MENU.mmb.sql` but have no source. `HRMS_DEPARTMENT`, `HRMS_LOV`, `HRMS_TOOLBAR` exist only in the README. |
| PL/SQL packages | 12 (lists `PKG_DEPARTMENT`) | **11** (`.pks` + `.pkb` each) | `PKG_DEPARTMENT` does not exist. |
| Tables | 42 | **30** | Counted from `CREATE TABLE` statements in `schema/tables/*.sql`. |
| Views | 15 | **6** | `schema/views/hrms_views.sql`. |
| Triggers | 200+ | **7** database triggers | 4 in `trg_employees.sql`, 3 in `trg_audit.sql`. (Forms-level triggers embedded in XML are not database triggers.) |
| Sequences | not stated | **29** | `schema/sequences/hrms_sequences.sql`. |
| Oracle Reports | 8 `.rdf/.rep` | **0** | No `.rdf` files anywhere in the repo. Referenced by `README.md`, `schema/views/hrms_views.sql` line 3 and `PKG_REPORTING.pks` line 7 only. |
| Other README directories | `plsql/procedures`, `plsql/functions`, `plsql/types`, `schema/indexes`, `schema/constraints`, `config`, `docs` | absent | Only `forms/`, `plsql/packages`, `plsql/triggers`, `schema/tables`, `schema/views`, `schema/sequences`, `data/seed` exist. |
| "Several packages exceed 3,000 lines" | README line 122 | Largest body is `PKG_EMPLOYEE.pkb` at 966 lines | See `TECH_DEBT_REGISTRY.md` ST-03. |

Also note: each form's header comment over-states its own block count (e.g. `HRMS_EMPLOYEE.xml` line 11
declares 5 data blocks, but only `EMPLOYEE` and `SALARY` are defined in the XML). The tables below list
only blocks that actually exist in the export.

---

## 1. Frontend Forms

Summary table (details per form follow).

| Component | Type | File path | Business purpose | Direct dependencies |
|---|---|---|---|---|
| HRMS_LOGIN | Oracle Form | `forms/xml-exports/HRMS_LOGIN.xml` | Authenticate user, establish `:GLOBAL` session state, open main menu | `PKG_SECURITY`; table `EMPLOYEES`; form `HRMS_MENU` |
| HRMS_MENU | Oracle Form | `forms/xml-exports/HRMS_MENU.xml` | Main navigation hub; permission-gated buttons/menu to child forms; logout | `HRMS_COMMON_LIB`; `PKG_SECURITY`; forms `HRMS_EMPLOYEE`, `HRMS_PAYROLL`, `HRMS_LEAVE`, `HRMS_PERFORMANCE`, `HRMS_REPORTS` (missing), `HRMS_ADMIN` (missing) |
| HRMS_EMPLOYEE | Oracle Form | `forms/xml-exports/HRMS_EMPLOYEE.xml` | Employee master maintenance with salary detail block and LOVs | `HRMS_COMMON_LIB`, `HRMS_VALIDATION_LIB`; menu `HRMS_MENU`; `PKG_SECURITY`, `PKG_EMPLOYEE`, `PKG_VALIDATION`; `SEQ_EMPLOYEE`; tables `EMPLOYEES`, `SALARY_RECORDS`, `DEPARTMENTS`, `JOB_TITLES`, `JOB_GRADES`, `LOCATIONS` |
| HRMS_PAYROLL | Oracle Form | `forms/xml-exports/HRMS_PAYROLL.xml` | Pay-period / payroll-run maintenance; create, calculate and approve runs | `HRMS_COMMON_LIB`; menu `HRMS_MENU`; `PKG_SECURITY`, `PKG_PAYROLL`; tables `PAY_PERIODS`, `PAYROLL_RUNS` |
| HRMS_LEAVE | Oracle Form | `forms/xml-exports/HRMS_LEAVE.xml` | Employee self-service leave requests, cancellation, balances | `HRMS_COMMON_LIB`; menu `HRMS_MENU`; `PKG_SECURITY`, `PKG_LEAVE`; tables `LEAVE_REQUESTS`, `LEAVE_BALANCES`, `LEAVE_TYPES` |
| HRMS_PERFORMANCE | Oracle Form | `forms/xml-exports/HRMS_PERFORMANCE.xml` | Review cycles, performance reviews and goals (master-detail-detail) | `HRMS_COMMON_LIB`; menu `HRMS_MENU`; `PKG_SECURITY`; tables `REVIEW_CYCLES`, `PERFORMANCE_REVIEWS`, `PERFORMANCE_GOALS`, `EMPLOYEES` |

### 1.1 HRMS_LOGIN (`forms/xml-exports/HRMS_LOGIN.xml`, 130 lines)

| Aspect | Value |
|---|---|
| Attached libraries | none |
| Menu module | none (login form) |
| Data blocks | `LOGIN` (control block, `QueryDataSourceType="None"`, line 29) |
| LOV / record groups | none |
| Direct SQL | `SELECT EMP_ID INTO :GLOBAL.current_emp_id FROM EMPLOYEES WHERE UPPER(EMAIL)=UPPER(:LOGIN.USERNAME) AND EMPLOYMENT_STATUS='ACTIVE' AND ROWNUM=1` (lines 86-90) |
| PKG_* calls | `PKG_SECURITY.authenticate(:LOGIN.USERNAME, :LOGIN.PASSWORD, GET_APPLICATION_PROPERTY(CLIENT_HOST))` (line 75, `WHEN-BUTTON-PRESSED`) |
| Globals written | `:GLOBAL.session_id`, `:GLOBAL.current_user`, `:GLOBAL.current_emp_id` (lines 82-86) |
| Navigation | `OPEN_FORM('HRMS_MENU', ACTIVATE, SESSION)` (line 93) |
| Header-documented issues | cleartext password transmission, no lockout, no CAPTCHA/2FA |

### 1.2 HRMS_MENU (`forms/xml-exports/HRMS_MENU.xml`, 175 lines)

| Aspect | Value |
|---|---|
| Attached libraries | `HRMS_COMMON_LIB` (line 16) |
| Menu module | embedded `<MenuModule Name="MENU_MAIN">` (lines 146-173); external `HRMS_MENU.mmb.sql` mirrors it |
| Data blocks | `MENU_CONTROL` (control block, line 43) |
| LOV / record groups | none |
| PKG_* calls | `PKG_SECURITY.has_permission(:GLOBAL.current_emp_id, 'PAYROLL'/'ADMIN'/'REPORTS', 'VIEW')` (lines 26, 30, 34, 75, 115); `PKG_SECURITY.logout(TO_NUMBER(:GLOBAL.session_id))` (lines 131, 149) |
| Navigation | `OPEN_FORM` to `HRMS_EMPLOYEE` (63,153), `HRMS_PAYROLL` (79,155), `HRMS_LEAVE` (91,157), `HRMS_PERFORMANCE` (103,159), `HRMS_REPORTS` (119,161 — **no source in repo**), `HRMS_ADMIN` (165 — **no source in repo**) |

### 1.3 HRMS_EMPLOYEE (`forms/xml-exports/HRMS_EMPLOYEE.xml`, 538 lines)

| Aspect | Value |
|---|---|
| Attached libraries | `HRMS_COMMON_LIB`, `HRMS_VALIDATION_LIB` (lines 22-23). Neither library's procedures/functions are actually invoked in the exported trigger text. |
| Menu module | `HRMS_MENU` (line 16) |
| Data blocks (actual) | `EMPLOYEE` -> `HRMS.EMPLOYEES` (line 110); `SALARY` -> `HRMS.SALARY_RECORDS` (line 431); relation `EMP_SALARY_REL` (line 453). Header (line 11) claims `DEPENDENTS`, `EMERGENCY_CONTACTS`, `EMP_HISTORY` blocks too, but they are not defined; only a `TP_DEPENDENTS` tab page exists (line 512). |
| LOV / record groups | `LOV_DEPARTMENTS`/`RG_DEPARTMENTS` -> `HRMS.DEPARTMENTS` (462-471); `LOV_JOB_TITLES`/`RG_JOB_TITLES` -> `HRMS.JOB_TITLES` join `HRMS.JOB_GRADES` (473-483); `LOV_MANAGERS`/`RG_MANAGERS` -> `HRMS.EMPLOYEES` (485-495); `LOV_LOCATIONS`/`RG_LOCATIONS` -> `HRMS.LOCATIONS` (497-507) |
| Inline SQL | `DEPARTMENTS` and `JOB_TITLES` display-name lookups in `WHEN-VALIDATE-ITEM` (lines 389-409); `POST-QUERY` lookups (345-368) |
| Sequence use | `:EMPLOYEE.EMP_ID := SEQ_EMPLOYEE.NEXTVAL` (`PRE-INSERT`, line 326) |
| PKG_* calls | `PKG_SECURITY.is_session_valid` (35), `PKG_SECURITY.has_permission(..., 'EMPLOYEE', 'EDIT')` (45), `PKG_EMPLOYEE.generate_emp_number` (327), `PKG_VALIDATION.validate_email_format` (377) |
| Client-side rules | hire date <= `SYSDATE + 90` (line 383) — DB trigger allows 180 days |
| Form triggers | `WHEN-NEW-FORM-INSTANCE` (28), `ON-ERROR` (66), `KEY-EXIT` (90), block `PRE-INSERT` (323), `PRE-UPDATE` (336), `POST-QUERY` (345), `WHEN-VALIDATE-ITEM` (370) |

### 1.4 HRMS_PAYROLL (`forms/xml-exports/HRMS_PAYROLL.xml`, 166 lines)

| Aspect | Value |
|---|---|
| Attached libraries | `HRMS_COMMON_LIB` (line 19) |
| Menu module | `HRMS_MENU` (line 16) |
| Data blocks (actual) | `PAY_PERIOD` -> `HRMS.PAY_PERIODS` (51); `PAYROLL_RUN` -> `HRMS.PAYROLL_RUNS` (69); relation `PERIOD_RUN_REL` (150). Header claims `PAYROLL_DETAIL`, `PAYSLIP_SUMMARY` blocks that are not defined. |
| LOV / record groups | none |
| PKG_* calls | `PKG_SECURITY.is_session_valid` (28); `PKG_SECURITY.has_permission(..., 'PAYROLL', 'VIEW')` (34), `(..., 'PAYROLL', 'APPROVE')` (137); `PKG_PAYROLL.create_payroll_run(:PAY_PERIOD.PERIOD_ID, 'REGULAR', :GLOBAL.current_user)` (98); `PKG_PAYROLL.calculate_payroll(:PAYROLL_RUN.RUN_ID, :GLOBAL.current_user)` (122); `PKG_PAYROLL.approve_payroll(:PAYROLL_RUN.RUN_ID, :GLOBAL.current_user)` (142) |

### 1.5 HRMS_LEAVE (`forms/xml-exports/HRMS_LEAVE.xml`, 219 lines)

| Aspect | Value |
|---|---|
| Attached libraries | `HRMS_COMMON_LIB` (line 19) |
| Menu module | `HRMS_MENU` (line 16) |
| Data blocks (actual) | `LEAVE_REQUEST` -> `HRMS.LEAVE_REQUESTS` (53); `NEW_REQUEST` control block (110); `LEAVE_BALANCE` -> `HRMS.LEAVE_BALANCES` (175). Header claims `PENDING_APPROVAL`, `TEAM_CAL` blocks that are not defined. |
| LOV / record groups | `LOV_LEAVE_TYPES`/`RG_LEAVE_TYPES` -> `HRMS.LEAVE_TYPES` (193-200) |
| Dynamic WHERE | `SET_BLOCK_PROPERTY(... DEFAULT_WHERE, 'EMP_ID = ' \|\| :GLOBAL.current_emp_id ...)` (line 36) |
| PKG_* calls | `PKG_SECURITY.is_session_valid` (25); `PKG_LEAVE.cancel_leave_request(:LEAVE_REQUEST.REQUEST_ID, 'Cancelled by employee', :GLOBAL.current_user)` (84); `PKG_LEAVE.submit_leave_request(p_emp_id => :GLOBAL.current_emp_id, ...)` (152) |

### 1.6 HRMS_PERFORMANCE (`forms/xml-exports/HRMS_PERFORMANCE.xml`, 131 lines)

| Aspect | Value |
|---|---|
| Attached libraries | `HRMS_COMMON_LIB` (line 18) |
| Menu module | `HRMS_MENU` (line 15) |
| Data blocks (actual) | `REVIEW_CYCLE` -> `HRMS.REVIEW_CYCLES` (40); `PERFORMANCE_REVIEW` -> `HRMS.PERFORMANCE_REVIEWS` (57); `PERFORMANCE_GOAL` -> `HRMS.PERFORMANCE_GOALS` (95); relations `CYCLE_REVIEW_REL` (90), `REVIEW_GOAL_REL` (116). Header claims a `REVIEW_DETAIL` block that is not defined. |
| LOV / record groups | none |
| Inline SQL | employee name lookup from `EMPLOYEES` in `POST-QUERY` (line 79 ff.) |
| PKG_* calls | `PKG_SECURITY.is_session_valid` (23) |

---

## 2. Shared Forms Libraries

| Component | Type | File path | Business purpose | Direct dependencies |
|---|---|---|---|---|
| HRMS_COMMON_LIB | Shared PLL library | `forms/libraries/HRMS_COMMON_LIB.pll.sql` (151 lines) | Common Forms helpers: `handle_error` (16), toolbar procedures `toolbar_save/clear/query/first/prev/next/last/insert/delete/exit` (44-99), `format_date`/`format_datetime` (101-112), `get_current_user`/`get_session_id` (114-125), `check_session` (127), `refresh_lov` (143) | `PKG_COMMON.log_error` (25), `PKG_SECURITY.is_session_valid` (134); `:GLOBAL.current_user`, `:GLOBAL.session_id` |
| HRMS_VALIDATION_LIB | Shared PLL library | `forms/libraries/HRMS_VALIDATION_LIB.pll.sql` (135 lines) | Client-side validators: `validate_email` (21), `validate_phone` (47), `validate_ssn` (69), `validate_date_not_future` (96), `validate_salary_range` (108) | Table `JOB_GRADES` (direct query, line 123). No PKG_* calls — duplicates `PKG_VALIDATION`/`PKG_COMMON` logic. |

Attachment matrix: `HRMS_COMMON_LIB` is attached to `HRMS_EMPLOYEE`, `HRMS_PAYROLL`, `HRMS_LEAVE`,
`HRMS_PERFORMANCE`, `HRMS_MENU`; `HRMS_VALIDATION_LIB` only to `HRMS_EMPLOYEE`. No exported form trigger
calls any library routine by name, so the libraries are attached but effectively unused in the checked-in exports.

---

## 3. Menu Module

| Component | Type | File path | Business purpose | Direct dependencies |
|---|---|---|---|---|
| HRMS_MENU (MMB) | Menu module | `forms/menus/HRMS_MENU.mmb.sql` (60 lines) | Documents the main menu tree: File (Logout/Exit), Modules (Employee, Payroll, Leave, Performance, Reports, Admin), Help. Items call `OPEN_FORM` for each module form. | Forms `HRMS_EMPLOYEE`, `HRMS_PAYROLL`, `HRMS_LEAVE`, `HRMS_PERFORMANCE`, `HRMS_REPORTS` (missing), `HRMS_ADMIN` (missing) (lines 42-47); `PKG_SECURITY.has_permission` (line 60) |

The same menu is duplicated inline in `HRMS_MENU.xml` lines 146-173 (`MENU_MAIN`), and again as buttons in
the `MENU_CONTROL` block — three copies of the navigation structure.

---

## 4. PL/SQL Business Logic Packages

Line counts: spec / body. "Stated" = `Dependencies:` comment in the `.pks` header. "Observed" = actual
`PKG_X.proc` calls in the `.pkb`. "DML" = `INSERT`/`UPDATE`/`DELETE` targets; "Reads" = `FROM`/`JOIN` targets.

| Component | Type | File path | Business purpose | Stated deps (.pks) | Observed PKG calls (.pkb) | Table DML (.pkb) | Table reads (.pkb) | Sequences |
|---|---|---|---|---|---|---|---|---|
| PKG_AUDIT | PL/SQL package | `plsql/packages/PKG_AUDIT.pks` (32) / `.pkb` (72) | Autonomous-transaction audit log writer, purge, change history | none | none | INSERT/DELETE `AUDIT_LOG` | `AUDIT_LOG` | `SEQ_AUDIT` |
| PKG_COMMON | PL/SQL package | `PKG_COMMON.pks` (121) / `.pkb` (283) | Logging (`log_error`, `log_info`), system parameters (`get_param*`, `set_param`), business-day/fiscal helpers, formatting, `is_valid_email/phone/ssn` | none | none | INSERT `AUDIT_LOG`; UPDATE `SYSTEM_PARAMETERS` | `SYSTEM_PARAMETERS` | `SEQ_AUDIT` |
| PKG_EMPLOYEE | PL/SQL package | `PKG_EMPLOYEE.pks` (192) / `.pkb` (966) | Employee lifecycle: create/update/search/transfer/promote/terminate/rehire, org chart, session context globals | `PKG_COMMON`, `PKG_AUDIT`, `PKG_NOTIFICATION`, `PKG_PAYROLL` | `PKG_PAYROLL.create_salary_record` (275, 617, 778), `PKG_AUDIT.log_action`, `PKG_NOTIFICATION.send_notification`, `PKG_COMMON.log_error` (`PKG_PAYROLL.calculate_final_pay` appears only in a TODO comment, line 739) | INSERT `EMPLOYEES`, `EMPLOYEE_HISTORY`; UPDATE `EMPLOYEES`, `SALARY_RECORDS`, `EMPLOYEE_PAY_ELEMENTS`, `LEAVE_REQUESTS` | `EMPLOYEES`, `DEPARTMENTS`, `JOB_TITLES`, `JOB_GRADES`, `SALARY_RECORDS`, `LEAVE_REQUESTS` | `SEQ_EMPLOYEE`, `SEQ_EMP_HISTORY` |
| PKG_INTEGRATION | PL/SQL package | `PKG_INTEGRATION.pks` (50) / `.pkb` (213) | Flat-file GL journal and ADP benefits exports, time/attendance import (stub), org sync (stub), integration status | `PKG_COMMON`, `PKG_PAYROLL`, `PKG_EMPLOYEE` | `PKG_COMMON.log_info`, `PKG_COMMON.log_error`, `PKG_COMMON.get_param` — **no** `PKG_PAYROLL`/`PKG_EMPLOYEE` calls | none (reads only, writes files via `UTL_FILE`) | `PAYROLL_DETAILS`, `PAYROLL_RUNS`, `PAY_PERIODS`, `PAY_ELEMENTS`, `EMPLOYEES`, `DEPARTMENTS`, `EMPLOYEE_DEPENDENTS`; `SYSTEM_PARAMETERS` via `PKG_COMMON.get_param` | none |
| PKG_LEAVE | PL/SQL package | `PKG_LEAVE.pks` (128) / `.pkb` (673) | Leave requests (submit/approve/reject/cancel), balances, monthly accrual, carryover, expiry, calendars | `PKG_EMPLOYEE`, `PKG_COMMON`, `PKG_AUDIT`, `PKG_NOTIFICATION` | `PKG_AUDIT.log_action`, `PKG_NOTIFICATION.send_notification` — **no** `PKG_EMPLOYEE`/`PKG_COMMON` calls | INSERT `LEAVE_REQUESTS`, `LEAVE_BALANCES`, `LEAVE_ACCRUAL_LOG`; UPDATE `LEAVE_REQUESTS`, `LEAVE_BALANCES` | `EMPLOYEES`, `LEAVE_TYPES`, `LEAVE_BALANCES`, `LEAVE_REQUESTS`, `HOLIDAYS` | `SEQ_LEAVE_REQUEST`, `SEQ_LEAVE_BALANCE`, `SEQ_LEAVE_ACCRUAL` |
| PKG_NOTIFICATION | PL/SQL package | `PKG_NOTIFICATION.pks` (42) / `.pkb` (177) | Queue notifications (autonomous), send queued e-mail via `UTL_SMTP`, retry, cancel | `PKG_COMMON` | `PKG_COMMON.log_error`, `PKG_COMMON.log_info` | INSERT/UPDATE `NOTIFICATION_QUEUE` | `EMPLOYEES`, `NOTIFICATION_QUEUE` | `SEQ_NOTIFICATION` |
| PKG_PAYROLL | PL/SQL package | `PKG_PAYROLL.pks` (164) / `.pkb` (897) | Salary records, pay periods, payroll runs, per-employee calculation, federal/state/FICA/Medicare tax, payslip, pay register file | `PKG_EMPLOYEE`, `PKG_COMMON`, `PKG_AUDIT`, `PKG_NOTIFICATION` | `PKG_COMMON.log_error`, `PKG_AUDIT.log_action` — **no** `PKG_EMPLOYEE` or `PKG_NOTIFICATION` calls in the body | INSERT/UPDATE `SALARY_RECORDS`, `PAY_PERIODS`, `PAYROLL_RUNS`, `PAYROLL_DETAILS` | `EMPLOYEES`, `DEPARTMENTS`, `SALARY_RECORDS`, `PAY_ELEMENTS`, `EMPLOYEE_PAY_ELEMENTS`, `EMPLOYEE_TAX_INFO`, `PAY_PERIODS`, `PAYROLL_RUNS`, `PAYROLL_DETAILS` | `SEQ_SALARY`, `SEQ_PAY_PERIOD`, `SEQ_PAYROLL_RUN`, `SEQ_PAYROLL_DETAIL` |
| PKG_PERFORMANCE | PL/SQL package | `PKG_PERFORMANCE.pks` (97) / `.pkb` (320) | Review cycles, reviews (self/manager/acknowledge), goals, team reporting, rating distribution | `PKG_EMPLOYEE`, `PKG_COMMON`, `PKG_AUDIT`, `PKG_NOTIFICATION` | `PKG_AUDIT.log_action`, `PKG_NOTIFICATION.send_notification` — **no** `PKG_EMPLOYEE`/`PKG_COMMON` calls | INSERT/UPDATE `REVIEW_CYCLES`, `PERFORMANCE_REVIEWS`, `PERFORMANCE_GOALS` | `EMPLOYEES`, `JOB_TITLES`, `DEPARTMENTS`, `PERFORMANCE_REVIEWS` | `SEQ_REVIEW_CYCLE`, `SEQ_PERF_REVIEW`, `SEQ_PERF_GOAL` |
| PKG_REPORTING | PL/SQL package | `PKG_REPORTING.pks` (63) / `.pkb` (207) | Ref-cursor reports: headcount, compensation, turnover, new hires, leave utilisation, payroll summary, EEO; `refresh_reporting_tables` stub | `PKG_EMPLOYEE`, `PKG_PAYROLL`, `PKG_COMMON` | `PKG_COMMON.log_info` only | none | `EMPLOYEES`, `DEPARTMENTS`, `LOCATIONS`, `JOB_TITLES`, `JOB_GRADES`, `SALARY_RECORDS`, `LEAVE_BALANCES`, `LEAVE_TYPES`, `PAYROLL_RUNS`, `PAYROLL_DETAILS` | none |
| PKG_SECURITY | PL/SQL package | `PKG_SECURITY.pks` (63) / `.pkb` (237) | Authentication, sessions, timeout, grade-threshold `has_permission`, SSN AES encrypt/decrypt, `change_password` (stub) | `PKG_COMMON`, `PKG_AUDIT` | `PKG_EMPLOYEE.set_session_context` (77), `PKG_AUDIT.log_action` — `PKG_EMPLOYEE` is **not** stated in the header | INSERT/UPDATE `USER_SESSIONS` | `EMPLOYEES`, `JOB_TITLES`, `USER_SESSIONS` | `SEQ_USER_SESSION` |
| PKG_VALIDATION | PL/SQL package | `PKG_VALIDATION.pks` (47) / `.pkb` (125) | Server-side validators: date range, salary-for-grade, email/phone format (delegated), emp-number format, future date, business day, required fields | `PKG_COMMON` | `PKG_COMMON.is_valid_email` (54), `PKG_COMMON.is_valid_phone` (61) | none | `JOB_GRADES`, `HOLIDAYS`, `EMPLOYEES` | none |

Package public global state (`PKG_EMPLOYEE.pks`): `g_current_user`, `g_current_emp_id`, `g_current_dept_id`,
`g_debug_mode` — set by `PKG_EMPLOYEE.set_session_context` (body lines 948-963), called from `PKG_SECURITY.authenticate`.

---

## 5. Database Tables

All DDL in `schema/tables/`. Column-level detail is in `DATA_DICTIONARY.md`. "Direct dependencies" lists
declared FK targets.

| Component | Type | File path (lines) | Business purpose | Direct dependencies (FKs) |
|---|---|---|---|---|
| DEPARTMENTS | Table | `schema/tables/01_core_tables.sql` 10-30 | Org departments / cost centers, self-referencing hierarchy | none declared (`PARENT_DEPT_ID`, `MANAGER_EMP_ID`, `LOCATION_CODE` are logical only) |
| LOCATIONS | Table | `01_core_tables.sql` 35-52 | Office locations (natural key `LOCATION_CODE`) | none |
| JOB_GRADES | Table | `01_core_tables.sql` 57-72 | Salary grades with min/max bands | none |
| JOB_TITLES | Table | `01_core_tables.sql` 77-93 | Job catalogue mapped to a grade | `JOB_GRADES` |
| EMPLOYEES | Table | `01_core_tables.sql` 98-147 | Employee master (core entity) | `DEPARTMENTS`, `JOB_TITLES`, `EMPLOYEES` (manager), `LOCATIONS` |
| EMPLOYEE_HISTORY | Table | `01_core_tables.sql` 152-177 | Employment change history (the repo's only "history" table; not `_HIST`-suffixed) | `EMPLOYEES` |
| EMPLOYEE_DEPENDENTS | Table | `01_core_tables.sql` 182-199 | Dependents for benefits | `EMPLOYEES` |
| EMERGENCY_CONTACTS | Table | `01_core_tables.sql` 204-220 | Emergency contacts | `EMPLOYEES` |
| SALARY_RECORDS | Table | `schema/tables/02_payroll_tables.sql` 10-32 | Effective-dated base salary | `EMPLOYEES` |
| PAY_ELEMENTS | Table | `02_payroll_tables.sql` 37-59 | Earning/deduction/tax/benefit element catalogue | none |
| EMPLOYEE_PAY_ELEMENTS | Table | `02_payroll_tables.sql` 64-81 | Employee-level element assignments | `EMPLOYEES`, `PAY_ELEMENTS` |
| PAY_PERIODS | Table | `02_payroll_tables.sql` 86-102 | Pay calendar | none |
| PAYROLL_RUNS | Table | `02_payroll_tables.sql` 107-131 | Payroll run header/status/totals | `PAY_PERIODS` |
| PAYROLL_DETAILS | Table | `02_payroll_tables.sql` 136-154 | Per-employee, per-element run lines (also error rows) | `PAYROLL_RUNS`, `EMPLOYEES`, `PAY_ELEMENTS` |
| TAX_BRACKETS | Table | `02_payroll_tables.sql` 159-173 | Tax bracket reference (not read by any package) | none |
| EMPLOYEE_TAX_INFO | Table | `02_payroll_tables.sql` 178-198 | W-4 style withholding info per year | `EMPLOYEES` |
| EMPLOYEE_BANK_ACCOUNTS | Table | `02_payroll_tables.sql` 203-225 | Direct-deposit accounts (not referenced by any package) | `EMPLOYEES` |
| LEAVE_TYPES | Table | `schema/tables/03_leave_tables.sql` 10-32 | Leave type catalogue and accrual rules | none |
| LEAVE_BALANCES | Table | `03_leave_tables.sql` 37-58 | Per-employee/type/year balances (virtual `AVAILABLE`) | `EMPLOYEES`, `LEAVE_TYPES` |
| LEAVE_REQUESTS | Table | `03_leave_tables.sql` 63-91 | Leave requests and approval workflow | `EMPLOYEES` (x2), `LEAVE_TYPES` |
| LEAVE_ACCRUAL_LOG | Table | `03_leave_tables.sql` 96-109 | Accrual audit trail | `EMPLOYEES`, `LEAVE_TYPES` |
| HOLIDAYS | Table | `03_leave_tables.sql` 114-124 | Company holidays (optionally per location) | none declared (`LOCATION_CODE` logical) |
| REVIEW_CYCLES | Table | `schema/tables/04_performance_tables.sql` 10-26 | Performance review cycles | none |
| PERFORMANCE_REVIEWS | Table | `04_performance_tables.sql` 31-59 | Individual reviews | `REVIEW_CYCLES`, `EMPLOYEES` (x2) |
| PERFORMANCE_GOALS | Table | `04_performance_tables.sql` 64-87 | Goals attached to reviews | `PERFORMANCE_REVIEWS`, `EMPLOYEES` |
| AUDIT_LOG | Table | `04_performance_tables.sql` 92-105 | Cross-cutting audit trail | none |
| SYSTEM_PARAMETERS | Table | `04_performance_tables.sql` 110-124 | Key/value configuration (incl. integration credentials per `PKG_INTEGRATION.pks`) | none |
| NOTIFICATION_QUEUE | Table | `04_performance_tables.sql` 129-148 | Outbound notification queue | none declared (`RECIPIENT_EMP_ID` logical) |
| USER_SESSIONS | Table | `04_performance_tables.sql` 153-165 | Forms login sessions | `EMPLOYEES` |
| LOOKUP_VALUES | Table | `04_performance_tables.sql` 170-182 | Generic lookups (not referenced by any package or form) | none declared (`PARENT_LOOKUP_ID` logical) |

---

## 6. Views

All in `schema/views/hrms_views.sql`.

| Component | Type | Lines | Business purpose | Direct dependencies (base tables) |
|---|---|---|---|---|
| VW_ACTIVE_EMPLOYEES | View | 10-40 | Denormalised active-employee lookup with dept, job, grade, manager, location, current salary | `EMPLOYEES` (x2), `DEPARTMENTS`, `JOB_TITLES`, `JOB_GRADES`, `LOCATIONS`, `SALARY_RECORDS` |
| VW_ORG_HIERARCHY | View | 47-57 | `CONNECT BY` org chart with level and path | `EMPLOYEES` |
| VW_EMPLOYEE_COMPENSATION | View | 63-80 | Salary vs. grade band, compa-ratio | `EMPLOYEES`, `DEPARTMENTS`, `JOB_TITLES`, `JOB_GRADES`, `SALARY_RECORDS` |
| VW_LEAVE_SUMMARY | View | 86-103 | Current-year balances and utilisation | `LEAVE_BALANCES`, `EMPLOYEES`, `DEPARTMENTS`, `LEAVE_TYPES` |
| VW_PAYROLL_LATEST | View | 109-129 | Gross/tax/deduction/net for latest approved run | `PAYROLL_DETAILS`, `EMPLOYEES`, `PAYROLL_RUNS` (x2), `PAY_PERIODS` |
| VW_PENDING_APPROVALS | View | 135-159 | Union of pending leave requests and reviews awaiting manager | `LEAVE_REQUESTS`, `EMPLOYEES`, `LEAVE_TYPES`, `PERFORMANCE_REVIEWS`, `REVIEW_CYCLES` |

No package or form in the repo selects from any of these views; the header (line 3) says they serve
Oracle Reports (absent) and external tools.

---

## 7. Sequences

All in `schema/sequences/hrms_sequences.sql` (29 sequences). Consumers are the packages/triggers/forms
that call `NEXTVAL`.

| Component | Type | Line | Populates | Consumers in repo |
|---|---|---|---|---|
| SEQ_DEPARTMENT | Sequence | 9 | `DEPARTMENTS.DEPT_ID` | none (seed inserts literal IDs) |
| SEQ_LOCATION | Sequence | 10 | `LOCATIONS` (PK is `LOCATION_CODE` VARCHAR2 — sequence has no natural target) | none |
| SEQ_JOB_GRADE | Sequence | 11 | `JOB_GRADES.GRADE_ID` | none |
| SEQ_JOB_TITLE | Sequence | 12 | `JOB_TITLES.JOB_ID` | none |
| SEQ_EMPLOYEE | Sequence | 13 | `EMPLOYEES.EMP_ID` | `PKG_EMPLOYEE.get_next_emp_id`, `generate_emp_number` fallback; `HRMS_EMPLOYEE.xml` `PRE-INSERT` |
| SEQ_EMP_HISTORY | Sequence | 14 | `EMPLOYEE_HISTORY.HIST_ID` | `PKG_EMPLOYEE.log_history`; `TRG_EMP_BEFORE_UPDATE` |
| SEQ_DEPENDENT | Sequence | 15 | `EMPLOYEE_DEPENDENTS.DEPENDENT_ID` | none |
| SEQ_EMERGENCY_CONTACT | Sequence | 16 | `EMERGENCY_CONTACTS.CONTACT_ID` | none |
| SEQ_EMP_NUMBER | Sequence | 21 | intended for `EMPLOYEES.EMP_NUMBER` | **none** — `generate_emp_number` uses `MAX()+1` instead (documented at lines 19-20) |
| SEQ_SALARY | Sequence | 24 | `SALARY_RECORDS.SALARY_ID` | `PKG_PAYROLL.create_salary_record` |
| SEQ_PAY_ELEMENT | Sequence | 25 | `PAY_ELEMENTS.ELEMENT_ID` | none |
| SEQ_EMP_PAY_ELEMENT | Sequence | 26 | `EMPLOYEE_PAY_ELEMENTS.EMP_ELEMENT_ID` | none |
| SEQ_PAY_PERIOD | Sequence | 27 | `PAY_PERIODS.PERIOD_ID` | `PKG_PAYROLL.create_pay_periods` |
| SEQ_PAYROLL_RUN | Sequence | 28 | `PAYROLL_RUNS.RUN_ID` | `PKG_PAYROLL.create_payroll_run` |
| SEQ_PAYROLL_DETAIL | Sequence | 29 | `PAYROLL_DETAILS.DETAIL_ID` | `PKG_PAYROLL` |
| SEQ_TAX_BRACKET | Sequence | 30 | `TAX_BRACKETS.BRACKET_ID` | none |
| SEQ_LEAVE_TYPE | Sequence | 33 | `LEAVE_TYPES.LEAVE_TYPE_ID` | none |
| SEQ_LEAVE_BALANCE | Sequence | 34 | `LEAVE_BALANCES.BALANCE_ID` | `PKG_LEAVE` |
| SEQ_LEAVE_REQUEST | Sequence | 35 | `LEAVE_REQUESTS.REQUEST_ID` | `PKG_LEAVE.submit_leave_request` |
| SEQ_LEAVE_ACCRUAL | Sequence | 36 | `LEAVE_ACCRUAL_LOG.ACCRUAL_ID` | `PKG_LEAVE.run_monthly_accrual` |
| SEQ_HOLIDAY | Sequence | 37 | `HOLIDAYS.HOLIDAY_ID` | none |
| SEQ_REVIEW_CYCLE | Sequence | 40 | `REVIEW_CYCLES.CYCLE_ID` | `PKG_PERFORMANCE` |
| SEQ_PERF_REVIEW | Sequence | 41 | `PERFORMANCE_REVIEWS.REVIEW_ID` | `PKG_PERFORMANCE` |
| SEQ_PERF_GOAL | Sequence | 42 | `PERFORMANCE_GOALS.GOAL_ID` | `PKG_PERFORMANCE` |
| SEQ_AUDIT | Sequence | 45 | `AUDIT_LOG.AUDIT_ID` (only sequence with `CACHE 100`) | `PKG_AUDIT.log_action`, `PKG_COMMON.log_error/log_info` |
| SEQ_NOTIFICATION | Sequence | 46 | `NOTIFICATION_QUEUE.NOTIFICATION_ID` | `PKG_NOTIFICATION.send_notification` |
| SEQ_USER_SESSION | Sequence | 47 | `USER_SESSIONS.SESSION_ID` | `PKG_SECURITY.authenticate` |
| SEQ_SYSTEM_PARAM | Sequence | 48 | `SYSTEM_PARAMETERS.PARAM_ID` | none |
| SEQ_LOOKUP | Sequence | 49 | `LOOKUP_VALUES.LOOKUP_ID` | none |

Tables with a surrogate PK but no sequence: `EMPLOYEE_TAX_INFO.TAX_INFO_ID`, `EMPLOYEE_BANK_ACCOUNTS.BANK_ACCT_ID`.

---

## 8. Database Triggers

| Component | Type | File path (lines) | Business purpose | Direct dependencies |
|---|---|---|---|---|
| TRG_EMP_BEFORE_INSERT | DB trigger (`BEFORE INSERT ON EMPLOYEES`) | `plsql/triggers/trg_employees.sql` 12-56 | Default audit columns, `ACTIVE_FLAG`, `EMPLOYMENT_STATUS`; reject hire date > `SYSDATE+180`; case-insensitive email uniqueness check | `EMPLOYEES` (self-query) |
| TRG_EMP_BEFORE_UPDATE | DB trigger (`BEFORE UPDATE ON EMPLOYEES`) | `trg_employees.sql` 62-112 | Set `MODIFIED_*`; block direct TERMINATED->ACTIVE; write status/department/job changes to `EMPLOYEE_HISTORY` | `EMPLOYEE_HISTORY`, `SEQ_EMP_HISTORY` (references `PKG_EMPLOYEE.rehire_employee` in a comment only) |
| TRG_EMP_INSTEAD_OF_DELETE | DB trigger (`BEFORE DELETE ON EMPLOYEES`) | `trg_employees.sql` 120-130 | Raises `-20504` on any DELETE (intended soft delete never happens) | `EMPLOYEES` |
| TRG_SALARY_AUDIT | DB trigger (`AFTER I/U/D ON SALARY_RECORDS`) | `plsql/triggers/trg_audit.sql` 10-41 | Build JSON by string concat, call audit logger | `PKG_AUDIT.log_action`; `SALARY_RECORDS` |
| TRG_LEAVE_REQUEST_AUDIT | DB trigger (`AFTER UPDATE OF STATUS ON LEAVE_REQUESTS`) | `trg_audit.sql` 47-60 | Audit status changes (action `'STATUS_CHANGE'`, which violates `CHK_AUDIT_ACTION`) | `PKG_AUDIT.log_action`; `LEAVE_REQUESTS` |
| TRG_DEPARTMENT_AUDIT | DB trigger (`AFTER I/U/D ON DEPARTMENTS`) | `trg_audit.sql` 66-84 | Audit department changes (no old/new values) | `PKG_AUDIT.log_action`; `DEPARTMENTS` |

Note: `TRG_EMP_BEFORE_UPDATE` inserts columns `HISTORY_ID`, `CHANGE_DATE`, `OLD_VALUE`, `NEW_VALUE`,
`CHANGED_BY`, `CHANGE_REASON` and change types `DEPARTMENT_CHANGE`/`JOB_CHANGE`, none of which exist in the
`EMPLOYEE_HISTORY` DDL (`HIST_ID`, `EFFECTIVE_DATE`, `OLD_/NEW_DEPT_ID`, ... and `CHK_CHANGE_TYPE`). The trigger
cannot compile against the checked-in schema. See `TECH_DEBT_REGISTRY.md` DI-04.

---

## 9. Seed / Reference Data

| Component | Type | File path | Business purpose | Direct dependencies (tables written) |
|---|---|---|---|---|
| 01_reference_data.sql | Seed script | `data/seed/01_reference_data.sql` (203 lines, 86 INSERTs) | Reference data: locations, departments, job grades, job titles, leave types, pay elements, holidays, system parameters | `LOCATIONS`, `DEPARTMENTS`, `JOB_GRADES`, `JOB_TITLES`, `LEAVE_TYPES`, `PAY_ELEMENTS`, `HOLIDAYS`, `SYSTEM_PARAMETERS` |
| 02_employee_data.sql | Seed script | `data/seed/02_employee_data.sql` (172 lines, 47 INSERTs + 7 UPDATEs) | Sample employees and salary records; back-fills `DEPARTMENTS.MANAGER_EMP_ID` | `EMPLOYEES`, `SALARY_RECORDS`; UPDATE `DEPARTMENTS` |

Seed/DDL mismatches (scripts will fail against the checked-in DDL): `LOCATIONS.PHONE` (DDL: `PHONE_NUMBER`),
`JOB_GRADES.GRADE_LEVEL` (no such column), `SYSTEM_PARAMETERS.DESCRIPTION` (DDL: `PARAM_DESCRIPTION`);
`02_employee_data.sql` line 166 then 167 assign two different managers to `DEPT_ID = 30`. Seed scripts
insert literal primary keys rather than using the sequences, so sequence `START WITH` values may collide
with seeded rows for sequences that start at 1 (e.g. `SEQ_SALARY START WITH 1` vs. seeded `SALARY_ID` 1-43;
`SEQ_EMPLOYEE` starting at 10000 does not collide with seeded `EMP_ID` values 1-99). The seed does not
contain FTP credentials; those are documented as living in `SYSTEM_PARAMETERS` by `PKG_INTEGRATION.pks` lines 11-12.
