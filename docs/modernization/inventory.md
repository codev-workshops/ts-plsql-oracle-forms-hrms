# HRMS Modernization — Inventory (companion to `checkpoint.md`)

> This is reference material only. **`checkpoint.md` is the entry point.** It holds the binding
> decisions and the re-homed logic registry; this file only catalogues what exists.
> Snapshot: `main@de01258`. Counts were taken from the files, not the README.

## I-1 Repository contents (verified)

| Area | Path | Count |
|---|---|---|
| Forms (XML exports) | `forms/xml-exports/` | 6: HRMS_LOGIN, HRMS_MENU, HRMS_EMPLOYEE, HRMS_LEAVE, HRMS_PAYROLL, HRMS_PERFORMANCE |
| Forms libraries (PLL source) | `forms/libraries/` | 2: HRMS_COMMON_LIB (18 routines), HRMS_VALIDATION_LIB (5 routines) |
| Menu module source | `forms/menus/HRMS_MENU.mmb.sql` | 1 (tree in comments) |
| PL/SQL packages | `plsql/packages/` | 11 spec/body pairs |
| DB triggers | `plsql/triggers/` | 6: TRG_EMP_BEFORE_INSERT, TRG_EMP_BEFORE_UPDATE, TRG_EMP_INSTEAD_OF_DELETE, TRG_SALARY_AUDIT, TRG_LEAVE_REQUEST_AUDIT, TRG_DEPARTMENT_AUDIT |
| Tables | `schema/tables/01..04_*.sql` | 30 |
| Sequences | `schema/sequences/hrms_sequences.sql` | 29 |
| Views | `schema/views/hrms_views.sql` | 6 |
| Seed data | `data/seed/01_reference_data.sql`, `02_employee_data.sql` | 2 |
| Build, tests, CI | — | none |

Forms referenced but **not present**: `HRMS_REPORTS`, `HRMS_ADMIN` (menu targets) and
`HRMS_DEPARTMENT` (named only in the PKG_EMPLOYEE header). There is no `config/` or `docs/`
directory on `main`; `docs/` is created by P0.

## I-2 Cross-package call graph (from `grep -noE "PKG_[A-Z_]+\.[a-zA-Z_]+"`)

Arrows mean real calls in code. Comments are excluded.

```
PKG_EMPLOYEE    -> PKG_PAYROLL.create_salary_record (x3: create_employee L275, promote_employee L617, rehire_employee L778)
                -> PKG_AUDIT.log_action, PKG_NOTIFICATION.send_notification, PKG_COMMON.log_error
PKG_SECURITY    -> PKG_EMPLOYEE.set_session_context, PKG_AUDIT.log_action
PKG_PAYROLL     -> PKG_AUDIT.log_action, PKG_COMMON.log_error              (NO PKG_EMPLOYEE call)
PKG_LEAVE       -> PKG_AUDIT.log_action, PKG_NOTIFICATION.send_notification (NO PKG_EMPLOYEE call)
PKG_PERFORMANCE -> PKG_AUDIT.log_action, PKG_NOTIFICATION.send_notification (NO PKG_EMPLOYEE call)
PKG_REPORTING   -> PKG_COMMON.log_info                                      (NO PKG_EMPLOYEE/PAYROLL call)
PKG_INTEGRATION -> PKG_COMMON.log_info/log_error/get_param                  (NO PKG_EMPLOYEE/PAYROLL call)
PKG_VALIDATION  -> PKG_COMMON.is_valid_email, PKG_COMMON.is_valid_phone
PKG_NOTIFICATION-> PKG_COMMON.log_info/log_error
PKG_AUDIT, PKG_COMMON -> (none)
trg_audit.sql   -> PKG_AUDIT.log_action (x3)
HRMS_COMMON_LIB -> PKG_SECURITY.is_session_valid, PKG_COMMON.log_error
Forms           -> PKG_SECURITY.{authenticate,is_session_valid,has_permission,logout},
                   PKG_EMPLOYEE.generate_emp_number, PKG_VALIDATION.validate_email_format,
                   PKG_LEAVE.{submit_leave_request,cancel_leave_request},
                   PKG_PAYROLL.{create_payroll_run,calculate_payroll,approve_payroll}
```

What the grep shows:
- `PKG_EMPLOYEE.is_active` is **never called**.
- `PKG_PAYROLL.calculate_final_pay` appears only in a TODO comment (PKG_EMPLOYEE.pkb L739) and
  **does not exist**.
- `PKG_EMPLOYEE.rehire_employee` appears in trg_employees.sql only inside a comment.
- The **documented EMPLOYEE↔PAYROLL cycle does not exist in code**; the only dependency is one-way
  EMPLOYEE→PAYROLL. PAYROLL, LEAVE and PERFORMANCE all query the `EMPLOYEES` table directly.
- The header `Dependencies:` lists are wrong:

| Package | Header claims | Actual |
|---|---|---|
| PKG_PAYROLL | EMPLOYEE, COMMON, AUDIT, NOTIFICATION | AUDIT, COMMON |
| PKG_LEAVE | EMPLOYEE, COMMON, AUDIT, NOTIFICATION | AUDIT, NOTIFICATION |
| PKG_PERFORMANCE | EMPLOYEE, COMMON, AUDIT, NOTIFICATION | AUDIT, NOTIFICATION |
| PKG_REPORTING | EMPLOYEE, PAYROLL, COMMON | COMMON |
| PKG_INTEGRATION | COMMON, PAYROLL, EMPLOYEE | COMMON |
| PKG_SECURITY | COMMON, AUDIT | AUDIT, EMPLOYEE |
| PKG_EMPLOYEE | COMMON, AUDIT, NOTIFICATION, PAYROLL | matches |

## I-3 Package API catalogue

### I-3.1 PKG_EMPLOYEE

| Routine | Notes |
|---|---|
| generate_emp_number | Race (DEF-08) |
| get_next_emp_id (private) | Wraps SEQ_EMPLOYEE |
| validate_dept (private) | -20003 |
| validate_manager (private) | -20004; cycle walk up to depth 15, only when an emp_id is given |
| log_history (private) | Autonomous transaction; swallows errors |
| create_employee | -20010, -20011; -20002 on DUP_VAL_ON_INDEX; calls create_salary_record when a salary is given |
| update_employee | Partial NVL update of name, email, phones, address; -20001 |
| get_employee / get_employee_by_number | -20001 |
| search_employees | Dynamic SQL; SQL injection |
| transfer_employee | `FOR UPDATE NOWAIT`; requires ACTIVE (-20012); validate_dept/manager; TRANSFER history |
| promote_employee | Updates job; create_salary_record(PROMOTION, change_pct); PROMOTION history; no ACTIVE check |
| terminate_employee | -20005 if already terminated. Auto-cancels PENDING leave without releasing balances. Status TERMINATED, ACTIVE_FLAG N. End-dates SALARY_RECORDS and EMPLOYEE_PAY_ELEMENTS. TERMINATION history. Manager notified |
| rehire_employee | validate_dept. TERMINATED→ACTIVE is blocked by trigger (DEF-13). New salary. REHIRE history |
| get_direct_reports, get_org_chart (CONNECT BY, max depth 10), get_headcount_by_dept, get_tenure_years | |
| is_active (unused), validate_employee, emp_exists, set_session_context | |

### I-3.2 PKG_PAYROLL

| Routine | Notes |
|---|---|
| create_salary_record | -20101 |
| get_current_salary / get_salary_as_of | Return 0 when not found |
| create_pay_periods | MONTHLY or BIWEEKLY; COMMIT |
| close_pay_period | -20102 |
| get_current_period | |
| create_payroll_run | Status PENDING; -20102 when the period is closed |
| calculate_payroll | **No status gate**; partial commits |
| calculate_employee_pay | -20104. Element IDs 1 (gross), 100 (fed), 101 (state), 102 (SS), 103 (Medicare). Deductions and benefits by PRIORITY_ORDER; `OVERRIDE_AMOUNT` > FLAT > PERCENTAGE |
| approve_payroll | Requires CALCULATED; -20103 |
| reverse_payroll | No check |
| calculate_federal_tax / calculate_state_tax / calculate_fica / calculate_medicare | 2024 constants |
| get_payslip | YTD placeholders |
| get_ytd_earnings | |
| generate_pay_register | UTL_FILE `PAYROLL_OUTPUT` |

### I-3.3 PKG_LEAVE

| Routine | Notes |
|---|---|
| calculate_business_days | |
| check_leave_overlap | PENDING/APPROVED; supports an exclude ID |
| submit_leave_request | Rules are in checkpoint PKG-L01 |
| approve_leave_request / reject_leave_request / cancel_leave_request | |
| get_leave_balance | `OPENING + ACCRUED - USED + ADJUSTMENT - PENDING` for the calendar year; 0 when missing |
| adjust_leave_balance | Current year; initializes the balance when missing |
| initialize_balances | |
| run_monthly_accrual / process_carryover / expire_carryover | |
| get_pending_requests (by APPROVER_EMP_ID) / get_team_calendar (APPROVED or TAKEN for direct reports) | |

### I-3.4 Other packages

**PKG_PERFORMANCE:** create_review_cycle (DRAFT), open_review_cycle (-20401), close_review_cycle,
create_review (ANNUAL / NOT_STARTED, notifies), submit_self_assessment (-20402),
submit_manager_review (-20403, label mapping), acknowledge_review, add_goal, update_goal_progress,
get_team_reviews, get_rating_distribution, generate_reviews_for_cycle (skips DUP_VAL_ON_INDEX,
COMMIT).

**PKG_SECURITY:** hash_password (MD5, unused), authenticate, logout, is_session_valid (30-minute
absolute; marks sessions EXPIRED), has_permission (grade-based), encrypt_ssn / decrypt_ssn
(AES-256-CBC with a hard-coded key), change_password (stub, -20310/-20311/-20312).

**PKG_AUDIT:** log_action (autonomous transaction, swallows errors), purge_old_records (365 days),
get_change_history.

**PKG_NOTIFICATION:** send_notification (autonomous transaction; queues into NOTIFICATION_QUEUE and
resolves the email from EMP_ID), process_queue (every 5 minutes, batch 50, UTL_MAIL),
retry_failed, cancel_notification.

**PKG_COMMON:**
- log_error / log_info, both JSON into AUDIT_LOG
- get_param / get_param_number / get_param_date / set_param (-20900)
- business_days_between, add_business_days
- get_fiscal_year / get_fiscal_quarter (fiscal year starts Oct 1)
- format_phone, format_ssn_masked, format_currency, format_name
- is_valid_email (regex), is_valid_phone, is_valid_ssn (9 digits)

**PKG_VALIDATION:** validate_date_range, validate_salary_for_grade (returns a message),
validate_email_format / validate_phone_format (delegate to PKG_COMMON),
validate_emp_number_format (`^EMP-\d{6}$`), is_future_date, is_business_day,
validate_required_fields (EMPLOYEES only).

**PKG_REPORTING:** headcount_report, compensation_summary, turnover_report, new_hires_report,
leave_utilization_report, payroll_summary_report, eeo_compliance_report,
refresh_reporting_tables (stub).

**PKG_INTEGRATION:** generate_gl_journal, export_benefits_feed (ADP fixed-width),
import_time_attendance (stub), sync_org_structure, get_integration_status.

## I-4 Security notes

- **Identity:** `authenticate` looks up the employee by `UPPER(EMAIL)` with `EMPLOYMENT_STATUS='ACTIVE'`,
  using MIN(emp_id) on TOO_MANY_ROWS. It creates a USER_SESSIONS row and returns SESSION_ID, and
  **never checks a password**: the comment says USER_CREDENTIALS is "simulated", and that table does
  not exist in the DDL.
- **Login form:** HRMS_LOGIN re-resolves EMP_ID with `ROWNUM=1`, and its `WHEN OTHERS` masks every
  error.
- **Session checks:** every form's WNFI passes the wrong value (`GET_APPLICATION_PROPERTY(USERNAME)`)
  to `is_session_valid`. HRMS_COMMON_LIB.check_session uses `:GLOBAL.session_id` correctly, but no
  form calls it.
- **Authorization:** checks are Forms-only for payroll approve (FRM-063), employee edit lockdown
  (FRM-030) and menu entries (FRM-010…012). Server packages do no authorization at all.
- **Data exposure:** `SSN_ENCRYPTED` is AES-256-CBC with a constant key and no IV management (the
  DBMS_CRYPTO default IV), and `decrypt_ssn` returns a sentinel on error. SYSTEM_PARAMETERS stores
  FTP credentials in cleartext (PKG_INTEGRATION header).

## I-5 Schema notes relevant to P1

- **Flags:** every `CHAR(1)` column is a Y/N flag except `EMPLOYEES.GENDER` (M/F/O). See checkpoint
  D-01.
- **Generated column:** `LEAVE_BALANCES.AVAILABLE` is `VIRTUAL` (03_leave_tables.sql L47).
- **CLOB/BLOB:** PERFORMANCE_REVIEWS has 6 assessment CLOBs. PERFORMANCE_GOALS has GOAL_DESCRIPTION
  and COMMENTS. AUDIT_LOG has OLD_VALUES and NEW_VALUES (JSON → JSONB). NOTIFICATION_QUEUE has BODY.
  EMPLOYEES has NOTES (CLOB) and PHOTO_BLOB.
- **Uniqueness:** EMPLOYEES has no unique constraint on EMAIL; only the trigger enforces it, among
  active rows. The unique keys that exist: UK_EMP_NUMBER, UK_DEPT_CODE, UK_GRADE_CODE, UK_JOB_CODE,
  UK_PAY_ELEM_CODE, UK_EMP_TAX_YEAR, UK_LEAVE_TYPE_CODE, UK_LEAVE_BAL(emp, type, year),
  UK_PARAM_CODE(group, code), UK_LOOKUP(type, code).
- **Status CHECKs** (the state-machine vocabularies):

| Column | Allowed values |
|---|---|
| EMPLOYEES.EMPLOYMENT_STATUS | ACTIVE, ON_LEAVE, SUSPENDED, TERMINATED |
| PAY_PERIODS.STATUS | OPEN, PROCESSING, CLOSED, REVERSED |
| PAYROLL_RUNS.STATUS | PENDING, CALCULATING, CALCULATED, APPROVED, PAID, REVERSED, ERROR |
| LEAVE_REQUESTS.STATUS | PENDING, APPROVED, REJECTED, CANCELLED, TAKEN |
| REVIEW_CYCLES.STATUS | DRAFT, OPEN, IN_PROGRESS, CALIBRATION, CLOSED |
| PERFORMANCE_REVIEWS.STATUS | NOT_STARTED, SELF_REVIEW, MANAGER_REVIEW, MEETING_SCHEDULED, COMPLETED, ACKNOWLEDGED |
| PERFORMANCE_GOALS.STATUS | NOT_STARTED, IN_PROGRESS, COMPLETED, DEFERRED, CANCELLED |
| AUDIT_LOG.ACTION_TYPE | INSERT, UPDATE, DELETE (see DEF-15) |
| NOTIFICATION_QUEUE.STATUS | PENDING, SENT, FAILED, CANCELLED |
| EMPLOYEE_HISTORY.CHANGE_TYPE | HIRE, TRANSFER, PROMOTION, DEMOTION, SALARY_CHANGE, TERMINATION, REHIRE, LEAVE_START, LEAVE_END, STATUS_CHANGE |

- **Other constraints:** CHK_RATING_RANGE (1.0–5.0), CHK_LR_DATES (end ≥ start),
  CHK_HALF_DAY (AM/PM/NULL), CHK_SALARY_RANGE (max ≥ min).
- **Actor columns:** all `CREATED_BY`/`CHANGED_BY` and `USER_SESSIONS.USERNAME` are `VARCHAR2(30)`
  (DEF-17).

## I-6 Sequences (29)

| Group | Sequences |
|---|---|
| START 100 | SEQ_DEPARTMENT, SEQ_LOCATION, SEQ_JOB_GRADE, SEQ_JOB_TITLE |
| START 10000 | SEQ_EMPLOYEE |
| START 1000 | SEQ_EMP_NUMBER (**defined but unused by code**; D-02 adopts it for EMP_NUMBER) |
| START 1 | SEQ_EMP_HISTORY, SEQ_DEPENDENT, SEQ_EMERGENCY_CONTACT, SEQ_SALARY, SEQ_PAY_ELEMENT, SEQ_EMP_PAY_ELEMENT, SEQ_PAY_PERIOD, SEQ_PAYROLL_RUN, SEQ_PAYROLL_DETAIL, SEQ_TAX_BRACKET, SEQ_LEAVE_TYPE, SEQ_LEAVE_BALANCE, SEQ_LEAVE_REQUEST, SEQ_LEAVE_ACCRUAL, SEQ_HOLIDAY, SEQ_REVIEW_CYCLE, SEQ_PERF_REVIEW, SEQ_PERF_GOAL, SEQ_NOTIFICATION, SEQ_USER_SESSION, SEQ_SYSTEM_PARAM, SEQ_LOOKUP |
| START 1, CACHE 100 | SEQ_AUDIT |

Several START-1 sequences (salary, pay element, leave type, holiday, system parameter) collide with
IDs that the seed data inserts explicitly. P1 must `setval` after seeding, per D-02.

## I-7 Views (6)

| View | Source / filter |
|---|---|
| VW_ACTIVE_EMPLOYEES | EMPLOYEES ⋈ DEPARTMENTS, JOB_TITLES, JOB_GRADES, manager, LOCATIONS, current SALARY_RECORDS; `EMPLOYMENT_STATUS='ACTIVE'` |
| VW_ORG_HIERARCHY | CONNECT BY from `MANAGER_EMP_ID IS NULL`, active only (→ recursive CTE) |
| VW_EMPLOYEE_COMPENSATION | Active employees ⋈ grade and current salary (compa-ratio) |
| VW_LEAVE_SUMMARY | LEAVE_BALANCES for `EXTRACT(YEAR FROM SYSDATE)` ⋈ employee, dept, type |
| VW_PAYROLL_LATEST | Details of the latest APPROVED run |
| VW_PENDING_APPROVALS | PENDING leave UNION ALL MANAGER_REVIEW performance reviews |

## I-8 Seed data vs DDL mismatches (P1 must reconcile)

| Seed reference | DDL |
|---|---|
| `LOCATIONS.PHONE` | `PHONE_NUMBER` |
| `JOB_GRADES.GRADE_LEVEL` | column absent; `GRADE_CODE NOT NULL` is not supplied |
| `SYSTEM_PARAMETERS.DESCRIPTION` | `PARAM_DESCRIPTION` |

The ID collisions with sequences are covered in I-6.
