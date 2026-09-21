# HRMS Technical Debt Registry

Scope: everything checked into this repository (Forms XML exports, PLL sources, menu, PL/SQL packages, triggers, DDL, seed data, README). Each item cites the file (and line where useful) that evidences it.

**Status legend**
- **Confirmed** – visible in the source (code, or an explicit `BUG:`/`VULNERABILITY:`/`TODO:` comment that the code corroborates).
- **Likely** – strongly implied by the source but would need a compile/run against Oracle to prove (e.g. column-name mismatches).
- **Review** – design smell or risk that depends on deployment context.

**Severity**: Critical / High / Medium / Low, judged on business impact (payroll money, PII, login) × likelihood.

Summary: 46 items – 11 Security, 7 Performance, 6 Validation drift / duplicated logic, 8 Correctness bugs & TODOs, 6 Architecture & coupling, 5 Schema / data drift, 3 Process & maintainability.

---

## 1. Security

| ID | Sev | Status | Where | Issue | Impact | Remediation |
|---|---|---|---|---|---|---|
| SEC-01 | Critical | Confirmed | `plsql/packages/PKG_SECURITY.pkb:6-7` | Symmetric encryption key for SSNs / bank accounts is a literal in the package body (flagged `VULNERABILITY:` in source). Anyone with `SELECT ON DBA_SOURCE`/`ALL_SOURCE` or the repo can decrypt every SSN. | Mass PII disclosure. | Move key to Oracle Wallet / TDE or a KMS; rotate key and re-encrypt `SSN_ENCRYPTED`, `ACCOUNT_NUMBER_ENC`. |
| SEC-02 | Critical | Confirmed | `PKG_SECURITY.pkb:16-24` (`hash_password`) | Passwords hashed with unsalted `DBMS_CRYPTO.HASH_MD5`. | Rainbow-table / brute-force recovery of all passwords if hash store leaks. | Salted PBKDF2/bcrypt/Argon2 (e.g. `DBMS_CRYPTO.PBKDF2` on 19c+ or app-tier hashing); force reset. |
| SEC-03 | Critical | Confirmed | `PKG_EMPLOYEE.pkb:442-466` (`search_employees`) | Dynamic SQL built by string concatenation of `p_last_name`, `p_first_name`, `p_emp_number` (`'... LIKE UPPER(''' || p_last_name || '%'') '`). Source labels it `SQL injection possible`. | Data exfiltration / modification through any caller other than the validated Forms LOV. | Bind variables (`USING`) or `DBMS_ASSERT.ENQUOTE_LITERAL`; better: static SQL with `NVL` predicates. |
| SEC-04 | High | Confirmed | `PKG_SECURITY.pkb:28` (`authenticate`) | No lockout / rate-limit after N failed logins (source comment). | Online brute force against MD5 passwords. | Failed-attempt counter + lockout in `USER_SESSIONS`/new table; CAPTCHA/2FA at Forms tier. |
| SEC-05 | High | Confirmed | `PKG_SECURITY.pkb:56-61`, `:225-235` (`change_password`) | Authentication is "simulated" – no `USER_CREDENTIALS` table exists in `schema/`; `change_password` only writes an audit row (stub). Any user whose e-mail matches can log in regardless of password path being exercised. | Login model incomplete; password change silently does nothing. | Implement credential store + verification; remove stub. |
| SEC-06 | High | Confirmed | `forms/xml-exports/HRMS_LOGIN.xml:11` | Password transmitted in cleartext from Forms applet (documented in header). | Credential sniffing on the network. | Enforce HTTPS/Forms SSL end-to-end; never pass raw password into PL/SQL – hash client-side or use SSO. |
| SEC-07 | High | Confirmed | `PKG_SECURITY.pkb:150-160` (`has_permission`), `HRMS_MENU.mmb.sql` | Authorization is derived from `JOB_GRADES.GRADE_ID` (≥8 = everything, ≥5 = view). No role/permission model; changing a salary grade changes access rights. | Privilege creep; cannot express "payroll clerk" without a high grade. | Introduce `ROLES`/`ROLE_PERMISSIONS` tables and check by permission code. |
| SEC-08 | Medium | Confirmed | `PKG_SECURITY.pkb:62`, `USER_SESSIONS.SESSION_ID` | Session token is `SEQ_USER_SESSION.NEXTVAL` (sequential integer) stored in `:GLOBAL.session_id`; `is_session_valid` only checks status + 30 min since `LOGIN_TIME` (not last activity). | Session guessing / fixation; sessions never extend on activity but also cannot be idle-timed properly. | Random 128-bit token (`DBMS_CRYPTO.RANDOMBYTES`), `LAST_ACTIVITY` column, bind to IP. |
| SEC-09 | Medium | Confirmed | `PKG_SECURITY.pkb:48-50` | Different code paths (NO_DATA_FOUND vs password mismatch) → timing side channel (source comment). | Username enumeration. | Constant-time compare and identical failure path. |
| SEC-10 | Medium | Confirmed | `PKG_SECURITY.pkb:52-57` | `TOO_MANY_ROWS` on login resolves to `MIN(EMP_ID)` – duplicate e-mails silently log in as the lowest ID. Uniqueness of `EMAIL` is only trigger-enforced (`TRG_EMP_BEFORE_INSERT`) and not on update. | Account takeover by registering a duplicate e-mail via update path. | Unique function-based index on `UPPER(EMAIL)`; fail login on duplicates. |
| SEC-11 | Medium | Confirmed | `schema/tables/02_payroll_tables.sql` (`EMPLOYEE_BANK_ACCOUNTS.ROUTING_NUMBER`), `PKG_PAYROLL.pkb:824+` (`generate_pay_register`), `PKG_INTEGRATION.pkb` | Routing numbers stored in clear; payroll register / GL / benefits feeds written as flat files via `UTL_FILE` to directory objects with no encryption or access control described. | PII/financial data at rest on DB host filesystem. | Encrypt or tokenise; replace file drops with authenticated API / SFTP with PGP. |

## 2. Performance

| ID | Sev | Status | Where | Issue | Impact | Remediation |
|---|---|---|---|---|---|---|
| PERF-01 | High | Confirmed | `PKG_PAYROLL.pkb:295` (`calculate_payroll`) | Row-by-row cursor loop calling `calculate_gross`/`calculate_taxes`/`calculate_deductions` per employee (source: `BUG: Cursor loop - should use BULK COLLECT + FORALL`). | Payroll run time grows linearly; each employee issues ~10 SQL round-trips. | Set-based calculation or `BULK COLLECT`/`FORALL` in batches. |
| PERF-02 | High | Confirmed | `PKG_PAYROLL.pkb:322-326` | `COMMIT` every 50 employees inside the loop (source: `ISSUE: Partial commits mean a failure leaves payroll half-calculated`). | A crash leaves a run in `CALCULATING` with partial `PAYROLL_DETAILS`; re-run duplicates lines. | Single transaction per run, or idempotent restart (delete details for run before recalculating). |
| PERF-03 | High | Confirmed | `schema/views/hrms_views.sql:45` (`VW_ORG_HIERARCHY`), `PKG_EMPLOYEE.pkb:835` (`get_org_chart`) | `CONNECT BY PRIOR EMP_ID = MANAGER_EMP_ID` over all active employees; DDL warns *performance degrades significantly with >500 employees*. No index on `MANAGER_EMP_ID` in DDL. | Slow org chart / manager lookups. | Index `EMPLOYEES(MANAGER_EMP_ID)`; consider recursive `WITH` and a materialised hierarchy table. |
| PERF-04 | Medium | Confirmed | `PKG_EMPLOYEE.pkb:37-45` (`generate_emp_number`) | `SELECT MAX(TO_NUMBER(SUBSTR(EMP_NUMBER,5)))+1` full scan with function on column, plus race condition (see BUG-01). | Serialises inserts; scan cost grows with table. | Use `SEQ_EMP_NUMBER` (already defined, unused). |
| PERF-05 | Medium | Confirmed | `PKG_PERFORMANCE.pkb` (`generate_reviews_for_cycle`), `PKG_LEAVE.pkb` (`process_monthly_accrual`, `process_carryover`) | Per-employee cursor loops with individual `INSERT`/`UPDATE` and notification enqueue. | Slow batch jobs; notification queue floods. | Set-based `INSERT … SELECT`; batch notifications. |
| PERF-06 | Medium | Confirmed | `schema/sequences/hrms_sequences.sql` | All sequences except `SEQ_AUDIT` are `NOCACHE`. | Sequence contention / latch waits under concurrent inserts (RAC especially). | `CACHE 20+`; gaps are acceptable for surrogate keys. |
| PERF-07 | Low | Confirmed | `schema/tables/*.sql`, README (`indexes/` folder absent) | Only PK/UK indexes exist; no indexes on FK columns (`EMP_ID` on child tables, `DEPT_ID`, `JOB_ID`, `PERIOD_ID`, `RUN_ID`) or on `EMPLOYEES.EMAIL` used by login. | Full scans on joins, lock escalation on parent deletes/updates. | Add FK indexes and `UPPER(EMAIL)` index. |

## 3. Validation drift & duplicated business rules

| ID | Sev | Status | Where | Issue | Impact | Remediation |
|---|---|---|---|---|---|---|
| VAL-01 | High | Confirmed | `HRMS_EMPLOYEE.xml:383-384` vs `plsql/triggers/trg_employees.sql:35-37` | Future hire-date limit is **90 days** in the form and **180 days** in the trigger. | Users see inconsistent errors; API callers can insert dates the UI forbids. | Single rule in `PKG_VALIDATION.validate_future_date`, called by both. |
| VAL-02 | High | Confirmed | `forms/libraries/HRMS_VALIDATION_LIB.pll.sql:39` vs `PKG_COMMON.is_valid_email` / `PKG_VALIDATION.validate_email_format` | PLL e-mail regex rejects sub-domain addresses (`user@mail.company.com`) that the server accepts (source `BUG:`). | Legitimate e-mails blocked at UI; login (which uses e-mail) affected. | Delete PLL copy; call `PKG_VALIDATION` from Forms. |
| VAL-03 | Medium | Confirmed | `HRMS_VALIDATION_LIB.pll.sql` (email, phone, SSN, future-date, salary-range) vs `PKG_VALIDATION`, `PKG_COMMON`, `PKG_EMPLOYEE.validate_employee` | Same rules implemented three times (PLL, `PKG_VALIDATION`, inline in `PKG_EMPLOYEE`/`PKG_PAYROLL`). | Any rule change must be made in 3 places; drift is guaranteed (see VAL-01/02). | Make `PKG_VALIDATION` the single source; PLL becomes thin wrapper. |
| VAL-04 | Medium | Confirmed | `HRMS_VALIDATION_LIB.pll.sql:104-122` | Header comment says salary validation uses a start-up cache that is never refreshed; the code actually queries `JOB_GRADES` live (source: *"comment/code mismatch"*). | Misleading documentation; maintainers may "fix" the wrong thing. | Correct the comment. |
| VAL-05 | Medium | Confirmed | `schema/tables/03_leave_tables.sql` (`LEAVE_BALANCES.AVAILABLE` virtual column) vs `schema/views/hrms_views.sql:96` (`VW_LEAVE_SUMMARY.AVAILABLE`) | Table computes `… - PENDING`; view omits `- PENDING`. | Reports over-state available leave by pending days. | View should select the virtual column. |
| VAL-06 | Medium | Confirmed | `PKG_SECURITY.pkb:8` vs `SYSTEM_PARAMETERS(SECURITY.SESSION_TIMEOUT_MIN)`; `PKG_NOTIFICATION.pkb:7-8` vs `NOTIFICATION.SMTP_HOST/FROM_ADDRESS`; `PKG_COMMON.get_fiscal_year` vs `PAYROLL.FISCAL_YEAR_START` | Configuration exists in `SYSTEM_PARAMETERS` (seeded) but packages use hard-coded constants instead of `PKG_COMMON.get_param`. | Admin edits to parameters have no effect. | Read via `get_param` (cache in package state if needed). |

## 4. Correctness bugs & unfinished work

| ID | Sev | Status | Where | Issue | Impact | Remediation |
|---|---|---|---|---|---|---|
| BUG-01 | High | Confirmed | `PKG_EMPLOYEE.pkb:37-45`, `schema/sequences/hrms_sequences.sql:19-21` | `generate_emp_number` uses `MAX()+1` with no lock → duplicate `EMP_NUMBER` under concurrent hires (`UK_EMP_NUMBER` violation). | Failed hires under load. | Use `SEQ_EMP_NUMBER`. |
| BUG-02 | High | Confirmed | `PKG_PAYROLL.pkb:605-714` (`calculate_taxes`) | Federal brackets hard-coded for 2024; state tax is a `CASE` with `ELSE 0.05` for unknown states; `TAX_BRACKETS` table is never read (`TODO`). Allowance amount `4300` hard-coded. | Wrong withholding from 2025; wrong tax for any state not listed. | Drive from `TAX_BRACKETS` (year + filing status + state). |
| BUG-03 | High | Likely | `plsql/triggers/trg_employees.sql` (`TRG_EMP_BEFORE_UPDATE`) vs `schema/tables/01_core_tables.sql` (`EMPLOYEE_HISTORY`) | Trigger inserts columns `HISTORY_ID, CHANGE_DATE, OLD_VALUE, NEW_VALUE, CHANGED_BY, CHANGE_REASON`; table defines `HIST_ID, EFFECTIVE_DATE, OLD_DEPT_ID/NEW_DEPT_ID …`. Trigger would fail to compile against this DDL (not verified by compiling). | Either the trigger is invalid (no history from Forms updates) or the DDL in repo is stale. | Align trigger with DDL or drop it in favour of `PKG_EMPLOYEE.log_history`. |
| BUG-04 | Medium | Confirmed | `PKG_LEAVE.pkb:606-623` (`expire_carryover`) | Subtracts `CARRYOVER_FROM_PREV` from `ADJUSTMENT` and zeroes it; source says *"If run twice on same day, can double-subtract"* – actually the `> 0` guard prevents that, but any rows where `USED` already consumed the carryover are over-deducted (no consideration of usage). | Employees lose leave they already took. | Expire only `GREATEST(0, CARRYOVER_FROM_PREV - USED)`; make idempotent with an `EXPIRED_FLAG`. |
| BUG-05 | Medium | Confirmed | `PKG_LEAVE.pkb:9`, `PKG_COMMON.is_business_day` | Holiday logic does not shift weekend holidays to observed weekdays (source `BUG:`). | Business-day counts off by one around observed holidays. | Store observed date in `HOLIDAYS.HOLIDAY_DATE` or compute observed day. |
| BUG-06 | Medium | Confirmed | `PKG_LEAVE.pkb:45-62` (`check_leave_overlap`) | Overlap test is date-range only; two half-day requests (AM+PM) on the same day are reported as overlapping. | Cannot book AM and PM separately. | Include `HALF_DAY_PERIOD` in the overlap predicate. |
| BUG-07 | Medium | Confirmed | `PKG_EMPLOYEE.pkb:737-739` (`terminate_employee`) | `TODO`: COBRA trigger, access revocation via `PKG_SECURITY`, final pay via `PKG_PAYROLL.calculate_final_pay` (which does not exist). Termination does not close `USER_SESSIONS`. | Terminated staff keep valid sessions; no final pay. | Implement; at minimum call `logout` for all sessions of the employee. |
| BUG-08 | Medium | Confirmed | `PKG_PAYROLL.pkb:784-785` (`get_payslip`), `PKG_INTEGRATION.pkb:170` & `:200`, `PKG_REPORTING.pkb:200` | Placeholders: payslip YTD gross/net = 0; time-attendance import parses nothing (`TODO`); `sync_org_structure` and `refresh_reporting_tables` only log. | Features advertised in README/spec do not work. | Implement or remove from spec and menu. |

## 5. Architecture & coupling

| ID | Sev | Status | Where | Issue | Impact | Remediation |
|---|---|---|---|---|---|---|
| ARCH-01 | High | Confirmed | `PKG_EMPLOYEE.pkb` → `PKG_PAYROLL.create_salary_record`; `PKG_PAYROLL.pks` header → `PKG_EMPLOYEE`; README:127 | Declared circular dependency `PKG_EMPLOYEE ⇄ PKG_PAYROLL` (direct call one way, declared the other). See `DEPENDENCY_MAP.md` §3. | Recompiling either invalidates the other; blocks independent deployment. | Move salary-record creation into a lower-level `PKG_SALARY` used by both, or event/queue. |
| ARCH-02 | High | Confirmed | `HRMS_EMPLOYEE.xml` (base-table blocks), `trg_employees.sql:5` | Business rules split between Forms triggers, DB triggers and packages; the form writes `EMPLOYEES`/`SALARY_RECORDS` directly, bypassing `PKG_EMPLOYEE` (no history, no notifications, no salary validation). Source calls the trigger approach an *anti-pattern*. | Two write paths with different rules. | Make blocks non-base-table (or use `INSTEAD OF` on a view) and route DML through `PKG_EMPLOYEE`. |
| ARCH-03 | Medium | Confirmed | `PKG_SECURITY.pkb:74` → `PKG_EMPLOYEE.set_session_context` (not declared in `PKG_SECURITY.pks` header) | Security package depends on employee package (upward dependency), undeclared. | Hidden coupling; `PKG_SECURITY` cannot be deployed standalone. | Move session context to `PKG_COMMON` or `PKG_SECURITY` itself. |
| ARCH-04 | Medium | Confirmed | `PKG_AUDIT.pkb:28-31`, `PKG_COMMON.log_error` (`WHEN OTHERS THEN ROLLBACK`) | Audit and error logging swallow all exceptions; `AUDIT_LOG.CHK_AUDIT_ACTION` rejects `'STATUS_CHANGE'` used by `TRG_LEAVE_REQUEST_AUDIT`, so those events vanish silently. | Audit trail incomplete with no alert. | Log to alert log / `DBMS_SYSTEM.KSDWRT` on failure; widen `ACTION_TYPE` domain. |
| ARCH-05 | Medium | Confirmed | `PKG_NOTIFICATION.pkb` (`UTL_SMTP`/`UTL_TCP`), `PKG_PAYROLL.generate_pay_register`, `PKG_INTEGRATION.*` (`UTL_FILE`), `PKG_EMPLOYEE.pkb:176,236` (`DBMS_OUTPUT` warnings) | Integration via raw SMTP from the DB, fixed-width ADP files (`LEGACY:` comment), GL CSV drops, and `DBMS_OUTPUT` for warnings. No retry/ack on file transfer. | Fragile, unobservable integrations; needs ACL/ directory grants on every environment. | App-tier integration service / Oracle AQ; structured logging. |
| ARCH-06 | Low | Confirmed | `README.md:120-127`, `plsql/packages/*.pkb` line counts | README claims packages exceed 3,000 lines, mixed CAMELCASE naming and dead code from decommissioned modules; checked-in bodies max at 966 lines (`PKG_EMPLOYEE.pkb`) and use consistent `UNDERSCORE_CASE`. README also lists ~20 artefacts that are not in the repo (see `APPLICATION_INVEONTORY.md` §8). | Documentation does not describe the code base; onboarding confusion. | Rewrite README from the inventory; delete or check in missing modules. |

## 6. Schema & data drift

| ID | Sev | Status | Where | Issue | Impact | Remediation |
|---|---|---|---|---|---|---|
| DATA-01 | High | Likely | `data/seed/01_reference_data.sql` vs DDL | Seed inserts use `LOCATIONS.PHONE` (DDL: `PHONE_NUMBER`), `JOB_GRADES.GRADE_LEVEL` (not in DDL) while omitting NOT NULL `GRADE_CODE`, and `SYSTEM_PARAMETERS.DESCRIPTION` (DDL: `PARAM_DESCRIPTION`). | Seed script fails on `ORA-00904`; environments cannot be bootstrapped from repo. | Fix seed column lists; add a CI step that runs DDL+seed against a container DB. |
| DATA-02 | Medium | Confirmed | `schema/tables/01_core_tables.sql` (`DEPARTMENTS`) | No FKs on `PARENT_DEPT_ID`, `MANAGER_EMP_ID`, `LOCATION_CODE`; `HOLIDAYS.LOCATION_CODE`, `NOTIFICATION_QUEUE.RECIPIENT_EMP_ID`, `SALARY_RECORDS.APPROVED_BY` also unconstrained. | Orphans; hierarchy loops possible (`CONNECT BY` will raise `ORA-01436`). | Add FKs (deferrable for the DEPARTMENTS⇄EMPLOYEES pair). |
| DATA-03 | Medium | Confirmed | `hrms_sequences.sql` vs DDL | No sequences for `EMPLOYEE_TAX_INFO.TAX_INFO_ID`, `EMPLOYEE_BANK_ACCOUNTS.BANK_ACCT_ID`; 14 of 29 sequences unused by any code; `SEQ_EMP_NUMBER` defined but bypassed. | Inconsistent key generation; manual IDs needed for two tables. | Add missing sequences (or identity columns on 19c); remove dead ones. |
| DATA-04 | Low | Confirmed | `04_performance_tables.sql` (`USER_SESSIONS.USERNAME VARCHAR2(30)`) vs `EMPLOYEES.EMAIL VARCHAR2(100)` | Login stores the e-mail as `USERNAME`; e-mails > 30 chars raise `ORA-12899` at login. | Login failure for long e-mails. | Widen to 100 or store `EMP_ID` only. |
| DATA-05 | Low | Confirmed | `LEAVE_REQUESTS.STATUS` (`TAKEN`), `LOOKUP_VALUES`, `EMERGENCY_CONTACTS`, `EMPLOYEE_BANK_ACCOUNTS`, `TAX_BRACKETS`, `LEAVE_ACCRUAL_LOG.RUN_ID`, `USER_SESSIONS.FORMS_MODULE` | Schema elements never written or read by any code in the repo. | Dead schema; unclear whether intentional roadmap or abandoned. | Decide and either wire up or drop. |

## 7. Process & maintainability

| ID | Sev | Status | Where | Issue | Impact | Remediation |
|---|---|---|---|---|---|---|
| PROC-01 | High | Confirmed | README:120; no `tests/` directory | No automated tests; README states *all testing is manual via Forms*. Nothing in the repo can be executed in CI. | Every change is a regression risk; the drift items above went unnoticed. | utPLSQL suite for packages; DDL+seed smoke test in a container. |
| PROC-02 | Medium | Confirmed | README (`procedures/`, `functions/`, `types/`, `indexes/`, `constraints/`, `config/`, `docs/`, `.rdf`, `HRMS_REPORTS`, `HRMS_ADMIN`, `HRMS_DEPARTMENT`, `HRMS_LOV`, `HRMS_TOOLBAR`), `HRMS_MENU.mmb.sql` (`OPEN_FORM('HRMS_REPORTS')`, `'HRMS_ADMIN'`) | Menu opens forms that are not in the repo; README documents directories that do not exist; no DDL for `DBMS_SCHEDULER` jobs or `UTL_FILE` directory objects that the packages assume. | Deploy from repo is impossible; runtime `FRM-40010` when opening missing forms. | Check in all artefacts or remove references; add scheduler/directory DDL under `schema/`. |
| PROC-03 | Low | Confirmed | `.fmb/.pll/.mmb` binaries absent; only XML/`.sql` text exports | Forms modules exist only as XML exports; binaries must be regenerated with `frmf2xml`/Forms Builder and there is no build script. | No reproducible build. | Add `forms/build.sh` using `frmf2xml -reverse` + `frmcmp_batch`; pin versions. |

---

## Suggested remediation order

1. **Stop the bleeding (security)** – SEC-01, SEC-02, SEC-03, SEC-05/SEC-06 first. Key rotation and password rehash need a data migration.
2. **Make the repo deployable** – DATA-01, BUG-03, PROC-02, then PROC-01 so subsequent fixes are testable.
3. **Payroll correctness** – BUG-02, PERF-01/02 together (same procedure).
4. **Consolidate validation** – VAL-01…VAL-06 via `PKG_VALIDATION` as single source; delete PLL duplicates.
5. **Decouple** – ARCH-01/02/03; move Forms to package-only DML.
6. Remaining Medium/Low items opportunistically.

Cross-references: component list in `APPLICATION_INVEONTORY.md`, dependency evidence in `DEPENDENCY_MAP.md`, column-level notes in `DATA_DICTIONARY.md`.
