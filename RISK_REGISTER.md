# Risk Register - Top 10 Migration Risks

Scoring: Likelihood and Impact 1 (low) - 5 (high); Score = L x I. Evidence cites files in this repository. "Owner" is the role that must decide or act; names to be assigned at Phase 0.

| # | Risk | L | I | Score | Phase exposed |
|---|---|---|---|---|---|
| R-01 | PL/SQL business logic that does not translate cleanly to Java | 5 | 4 | 20 | 1-4, 6 |
| R-02 | Circular `PKG_EMPLOYEE` <-> `PKG_PAYROLL` dependency (compilation / invalidation cascade) | 4 | 4 | 16 | 0, 3, 4 |
| R-03 | PII fields (SSN, bank, DOB, dependents) and hard-coded encryption key | 4 | 5 | 20 | 0, 3, 4 |
| R-04 | Environment gaps (no tests, no CI, binaries not versioned, missing modules, Oracle-only features) | 5 | 4 | 20 | 0 |
| R-05 | Authentication is not actually implemented in source; credential store unknown | 4 | 5 | 20 | 0 |
| R-06 | Three-way logic duplication (Forms triggers / DB triggers / packages) causes behaviour drift during coexistence | 4 | 4 | 16 | 1-4 |
| R-07 | Payroll financial parity and tax-year correctness | 3 | 5 | 15 | 4 |
| R-08 | Transaction and concurrency semantics (autonomous transactions, partial commits, pessimistic locks, `MAX()+1`) | 4 | 4 | 16 | 2-4 |
| R-09 | Batch jobs and file/mail integrations tied to `DBMS_SCHEDULER`, `UTL_FILE`, `UTL_SMTP` | 3 | 4 | 12 | 2, 4, 5 |
| R-10 | Authorization regression: permissions enforced only in the Forms UI, not in packages | 4 | 4 | 16 | 0-4 |

---

## R-01 PL/SQL business logic that does not translate cleanly

**Description.** Several rules depend on Oracle semantics that Java does not share, so a line-by-line port silently changes behaviour.

**Evidence.**
- `NULL`/empty-string equivalence: `IF p_last_name IS NOT NULL` (`PKG_EMPLOYEE.search_employees`), `NVL(:NEW.DEPT_ID,-1)` in `TRG_EMP_BEFORE_UPDATE`; Java `""` != `null`.
- `DATE` has time; rules use `TRUNC(SYSDATE)` in some places (`PKG_LEAVE.submit_leave_request`, `expire_carryover`) and raw `SYSDATE` in others (`HIRE_DATE > SYSDATE + 180`). `LocalDate` vs `LocalDateTime` choice changes boundary results.
- `NUMBER` arithmetic: `ROUND(v_annual_salary / v_periods_per_year, 2)` and percentage deductions rely on Oracle decimal semantics; `double` in Java will drift by cents (must be `BigDecimal` with `HALF_UP`).
- `CONNECT BY` hierarchy (`get_org_chart`, `VW_ORG_HIERARCHY`, cycle detection loop depth 15).
- Analytic SQL `COUNT(*)*100.0/SUM(COUNT(*)) OVER ()` (`get_rating_distribution`).
- Implicit conversions: `TO_NUMBER(SUBSTR(EMP_NUMBER,5))`, `TO_CHAR(v_date,'DY','NLS_DATE_LANGUAGE=AMERICAN')` for weekend detection (locale-dependent).
- Fiscal year rules (`PKG_COMMON.get_fiscal_year`, FY starts October) duplicated as a hard-coded assumption in `PKG_REPORTING` (per its comments).
- Exception-swallowing patterns: `WHEN OTHERS THEN NULL` in `log_history`, `DUP_VAL_ON_INDEX` ignored in `generate_reviews_for_cycle`, autonomous `log_error` that never fails the caller.

**Impact.** Wrong leave days at month/year boundaries, off-by-one-cent payroll, org chart differences, silently lost audit rows.

**Mitigation.**
- Characterization tests first (TEST_STRATEGY 3.1): generate inputs, run both implementations, diff outputs; port only when parity is proven, then deliberately change behaviour with a new test.
- Coding standards: `BigDecimal` everywhere money/percent appears; `LocalDate` for business dates with explicit `truncate` decisions documented per field; `Optional`/blank normalisation at API boundary.
- Keep complex set-based SQL (`CONNECT BY`, analytic) **in SQL** executed from Java rather than re-implemented in loops.
- Hybrid strategy for employee/payroll (BLUEPRINT 3.1/3.2) postpones the hardest translations until characterization coverage exists.

**Owner / decision.** Tech lead; decide `TRUNC` policy per date field and rounding mode before Phase 1.

**Detection / exit.** Parity suite green for each ported function; zero unexplained diffs in shadow runs.

---

## R-02 Circular `PKG_EMPLOYEE` <-> `PKG_PAYROLL` dependency

**Description.** `PKG_EMPLOYEE.create_employee` calls `PKG_PAYROLL.create_salary_record`; `PKG_PAYROLL.calculate_employee_pay` (and `terminate_employee` paths) reference `PKG_EMPLOYEE`. Spec-level compilation works only because both specs are compiled before bodies; any spec change invalidates both bodies and everything that depends on them (Forms libraries, triggers, `PKG_LEAVE`, `PKG_SECURITY.authenticate -> PKG_EMPLOYEE.set_session_context`). README lists this as known technical debt.

**Evidence.** `PKG_EMPLOYEE.pks` header comment ("Circular dependency with PKG_PAYROLL"), `create_employee` body, `PKG_PAYROLL.pkb` calls to `PKG_EMPLOYEE`; `PKG_SECURITY.authenticate` -> `PKG_EMPLOYEE.set_session_context`.

**Impact.** Deploying a Phase 0/3/4 package change can leave objects `INVALID` mid-day, causing `ORA-04068 existing state of packages has been discarded` for logged-in Forms users (packages hold session state: `g_current_user`, `g_current_emp_id`). Also blocks migrating payroll independently of employee.

**Mitigation.**
- Phase 0: extract the shared surface into a new leaf package `PKG_SALARY_CORE` (salary record CRUD, `get_salary_as_of`) with no dependencies on either; `PKG_EMPLOYEE` and `PKG_PAYROLL` depend on it, not on each other. Move `set_session_context` out of `PKG_EMPLOYEE` into `PKG_SESSION_CTX` (or use `DBMS_SESSION.SET_CONTEXT`).
- Verify with `SELECT * FROM user_dependencies WHERE referenced_name IN (...)` in CI; fail the build on cycles.
- Deploy package changes in maintenance windows until Forms is retired; use edition-based redefinition if available (19c EE) to avoid `ORA-04068`.
- Java side: employee and payroll modules communicate through interfaces / domain events, never bidirectionally.

**Owner / decision.** DBA + tech lead; approve `PKG_SALARY_CORE` design.

**Detection / exit.** `user_objects` shows zero `INVALID`; dependency query shows DAG; `ORA-04068` count in Forms logs = 0 after deployments.

---

## R-03 PII fields and hard-coded encryption key

**Description.** Sensitive data is spread over many tables and protected by a key that is in source control.

**Evidence.**
- `PKG_SECURITY.pkb`: `c_encryption_key RAW(32) := UTL_RAW.CAST_TO_RAW('...')` (AES-256-CBC); `hash_password` = MD5.
- `EMPLOYEES.SSN_ENCRYPTED`, `DATE_OF_BIRTH`, `GENDER`, `MARITAL_STATUS`, `NATIONALITY`, addresses, `PHOTO`, `NOTES`; `EMPLOYEE_DEPENDENTS.SSN_ENCRYPTED`; `EMPLOYEE_BANK_ACCOUNTS.ACCOUNT_NUMBER_ENCRYPTED`, `ROUTING_NUMBER`; `EMPLOYEE_TAX_INFO` (W-4); `AUDIT_LOG.OLD_VALUES/NEW_VALUES` CLOBs that may contain any of the above; `SYSTEM_PARAMETERS` documented as storing FTP credentials; `USER_SESSIONS.IP_ADDRESS`.
- Seed data (`data/seed/02_employee_data.sql`) contains realistic-looking names, DOBs and e-mails - synthetic, but the pattern invites copying production data into test environments.

**Impact.** Regulatory exposure (SSN, bank), key compromise means all historic ciphertext is readable; test environments and logs become PII stores; screenshots in PRs leak data.

**Mitigation.**
- Phase 0: re-encrypt all `*_ENCRYPTED` columns under a KMS/Vault-managed key with envelope encryption; keep key IDs per row for rotation; delete the literal from the package (retain a decrypt-only migration utility with the legacy key injected at runtime, then remove).
- Data classification table (column -> class -> masking rule) checked into `db/`; DTOs default to masked (`***-**-1234`) with explicit `SSN_VIEW` permission for unmasked reads, logged to `AUDIT_LOG`.
- Log redaction filter (regex for SSN/account patterns) in the API; ban PII from `AUDIT_LOG` JSON except last-4.
- Test data: synthetic only (TEST_STRATEGY 4); if a masked production clone is required, use Oracle Data Masking / custom masking pipeline with sign-off; no production data on developer machines.
- Bank/tax tables have no package today - the new API is the first place they get access control; design it before exposing.

**Owner / decision.** Security officer + data owner (HR); approve KMS choice, classification, and whether a masked production subset is permitted.

**Detection / exit.** Secret scanner clean; unmasked-PII grep over logs/responses in CI = 0 hits; key rotation rehearsed once.

---

## R-04 Environment gaps

**Description.** The repository is not sufficient to build, run or verify the legacy application.

**Evidence.**
- README: "No unit tests"; no CI configuration; `.gitignore` excludes `*.fmb, *.mmb, *.pll, *.rdf` - only XML exports of 6 of the 18 forms and none of the 8 reports are present; `HRMS_REPORTS`, `HRMS_ADMIN` referenced by the menu are absent.
- Runtime dependencies not reproducible from repo: `DBMS_SCHEDULER` jobs, directory objects (`PAYROLL_OUTPUT`, GL/benefits/T&A dirs), `UTL_SMTP` ACLs, `USER_CREDENTIALS` table, Forms connection user/grants, `TAX_BRACKETS` contents, `HOLIDAYS` beyond 2024.
- Schema scripts are `CREATE TABLE HRMS.*` with no migration tool or baseline; sequence definitions and 200+ triggers claimed by README are only partially present.
- Oracle 19c + Forms 12c + WebLogic licences/environments needed for parity testing; Oracle Free/XE containers do not run Forms.

**Impact.** Cannot establish a trustworthy behavioural baseline; parity tests compare against an incomplete legacy; surprises at cutover from undocumented triggers/jobs.

**Mitigation.**
- Phase 0 inventory: export full DDL (`DBMS_METADATA`), all packages/triggers/jobs/directories/ACLs from production into `db/baseline/`; export all `.fmb/.pll/.mmb` to XML with `frmf2xml` and commit; reconcile against README counts and record the delta.
- Provision: (1) an Oracle 19c clone of production with masked data for Forms + PL/SQL parity, (2) Oracle Free 23ai in Testcontainers for CI (packages compile there; `UTL_FILE`/`DBMS_SCHEDULER` mocked).
- Introduce Flyway baseline and utPLSQL; CI compiles every package and fails on `INVALID`.
- Treat "18 forms" vs 6 as a scope-risk: re-estimate Phase 3-5 after inventory.

**Owner / decision.** Platform/DBA lead; approve environment budget and production-export access.

**Detection / exit.** `db/baseline/` diff against production = empty; CI green from a clean clone.

---

## R-05 Authentication not implemented in source; credential store unknown

**Description.** The only authentication code in the repo accepts any password.

**Evidence.** `PKG_SECURITY.authenticate` never uses `p_password`; comment: "In the real system, passwords are stored in a separate USER_CREDENTIALS table"; `change_password` is a stub; `hash_password` is MD5; `HRMS_LOGIN.xml` comment notes cleartext transmission; no lockout; duplicate e-mails resolved to `MIN(EMP_ID)`.

**Impact.** Cannot "migrate" credentials that the repo does not model; risk of shipping a new app that trusts legacy semantics; MD5 hashes (if they exist) cannot be upgraded without a reset or on-login rehash.

**Mitigation.**
- Delegate authentication to the corporate IdP (OIDC); map users by `EMPLOYEES.EMAIL` (enforce uniqueness among active employees first - trigger `-20502` only checks inserts).
- If local passwords must be kept: import `USER_CREDENTIALS` into the IdP or force reset; never re-implement MD5 in Java.
- Forms keeps its own login during coexistence; both write `USER_SESSIONS` for audit.

**Owner / decision.** Security officer; confirm production credential model and IdP availability in Phase 0.

**Detection / exit.** Penetration test of login; zero MD5 references in new code.

---

## R-06 Three-way logic duplication and behaviour drift

**Description.** The same rules exist in Forms triggers, DB triggers and packages with different values.

**Evidence.** Hire date: `HRMS_EMPLOYEE.xml` `WHEN-VALIDATE-ITEM` (<= today+90) vs `TRG_EMP_BEFORE_INSERT` (<= today+180). Defaults (`ACTIVE_FLAG='Y'`, `EMPLOYMENT_STATUS='ACTIVE'`) set in form `PRE-INSERT`, trigger and `create_employee`. History rows written by `TRG_EMP_BEFORE_UPDATE` **and** by `PKG_EMPLOYEE.log_history` -> a package-driven transfer produces two history rows. E-mail validation regex differs between `HRMS_VALIDATION_LIB` and `PKG_COMMON.is_valid_email` (per library comment). `trg_employees.sql` header explicitly calls this an anti-pattern.

**Impact.** During coexistence the same action yields different outcomes depending on which UI performed it; parity tests "fail" on legacy inconsistencies; duplicated audit/history rows.

**Mitigation.** Declare precedence (package > trigger > form) and record each rule once in a rules catalogue (COMPONENT_MAPPING lists them per module); resolve conflicts explicitly (decision log); make the API call packages (never direct DML) so triggers become idempotent backstops; add `DBMS_APPLICATION_INFO.SET_MODULE('HRMS_API')` so triggers can skip duplicate history writes for API calls.

**Owner / decision.** Product owner (HR) for rule conflicts; tech lead for precedence.

**Detection / exit.** Rules catalogue signed off; duplicate `EMPLOYEE_HISTORY` rows per change = 0 in reconciliation job.

---

## R-07 Payroll financial parity and tax-year correctness

**Description.** Payroll is the only module where a defect is a direct monetary loss and a compliance event.

**Evidence.** `calculate_federal_tax` brackets, standard deductions (14,600 / 29,200), allowance value (4,300) and `c_ss_wage_base := 168600` hard-coded for 2024 with `TODO: Read from TAX_BRACKETS`; only `SINGLE|MARRIED_SEPARATE` and `MARRIED_JOINT` handled - any other filing status yields **zero** federal tax; `calculate_state_tax` uses simplified flat rates and a default for unknown states; `get_payslip` returns `0 AS YTD_GROSS`; `PAYROLL_DETAILS ELEMENT_TYPE='ERROR'` rows; run commits every 50 employees.

**Impact.** Rewriting the engine against buggy legacy means choosing between "match the bug" (parity) and "be correct" (compliance) - both must be explicit. Tax year 2025+ constants are absent from source.

**Mitigation.** Hybrid first (BLUEPRINT 3.2): UI/API over `PKG_PAYROLL`, then shadow-run the Java engine for >= 3 periods with cent-level reconciliation; every intentional deviation recorded as a "known legacy defect" with finance sign-off; `TAX_BRACKETS` becomes the single source for both engines during shadow; golden-file test set with edge cases (wage-base crossover, additional Medicare threshold, mid-period salary change, terminated employee, unknown state).

**Owner / decision.** Finance/payroll manager; approve parity-vs-correctness list and tax-table stewardship.

**Detection / exit.** 3 consecutive shadow periods with zero unexplained deltas; register and GL files byte-identical.

---

## R-08 Transaction and concurrency semantics

**Description.** Legacy relies on Oracle transaction features that a stateless API changes.

**Evidence.**
- `PRAGMA AUTONOMOUS_TRANSACTION` in `PKG_AUDIT.log_action`, `PKG_COMMON.log_error`, `PKG_EMPLOYEE.log_history`, `PKG_NOTIFICATION.send_notification` - audit rows survive a rollback of the main transaction.
- Partial commits: `calculate_payroll` every 50 employees; `run_monthly_accrual` every 100; `generate_reviews_for_cycle` commits at end while callers may also commit.
- Pessimistic locking: Forms `ON-LOCK`, `SELECT ... FOR UPDATE` in `approve_payroll`, `close_pay_period`; `FRM-40501` handling in `HRMS_EMPLOYEE.xml`.
- Race: `generate_emp_number` `MAX(...)+1` "may produce duplicates under concurrent inserts" (source comment).
- Package session state (`g_current_user`, `g_current_emp_id`) assumes one DB session per user; a connection pool breaks this (context bleeds between users).

**Impact.** Lost audit on failure, duplicate employee numbers, double accrual, lost-update conflicts between Forms and web users, wrong `CREATED_BY` if package globals are reused across pooled connections.

**Mitigation.** `REQUIRES_NEW` for audit/notification writes; per-employee transactions with idempotency keys for batch; optimistic locking (version column) in API with 409 handling, and `FOR UPDATE NOWAIT` when calling legacy procedures that lock; sequence-based numbering; never rely on package globals - pass `p_user` explicitly (every package procedure already accepts it) and clear `set_session_context` after each call, or move to `DBMS_SESSION` context set per request.

**Owner / decision.** Tech lead; choose optimistic vs pessimistic policy per table.

**Detection / exit.** Concurrency tests (50 parallel creates, Forms + API edit of the same row) pass; audit rows present after forced rollbacks.

---

## R-09 Batch jobs and file/mail integrations

**Description.** Server-side jobs and I/O are Oracle-internal and invisible to the new stack.

**Evidence.** `PKG_LEAVE.run_monthly_accrual` (DBMS_SCHEDULER per comments), `PKG_REPORTING.refresh_reporting_tables` nightly, `PKG_PAYROLL.generate_pay_register` -> `UTL_FILE` `PAYROLL_OUTPUT`, `PKG_INTEGRATION` GL/benefits export and T&A import via `UTL_FILE` with vendor (ADP) format and "no retries", `PKG_NOTIFICATION.process_queue` via `UTL_SMTP` to hard-coded `smtp.internal.company.com:25`, cleartext FTP credentials in `SYSTEM_PARAMETERS`.

**Impact.** Double execution (both schedulers running), missed runs at switch-over, downstream file consumers breaking on format/transport change, duplicate e-mails.

**Mitigation.** One scheduler owner per job at any time, switched at natural boundaries (month end for accrual, after approval for GL); byte-identical file contract tests before switching transport; notification outbox with idempotency; move secrets to Vault; keep `NOTIFICATIONS` table as the shared queue so both stacks can enqueue while only one dispatches.

**Owner / decision.** Integration owner; confirm downstream systems' acceptance and switch dates.

**Detection / exit.** Job-run ledger shows exactly one execution per schedule; files diff-clean; duplicate-notification count 0.

---

## R-10 Authorization enforced only in the Forms UI

**Description.** Packages trust their callers; the web tier becomes the first server-side enforcement point.

**Evidence.** `HRMS_MENU.xml` and `HRMS_PAYROLL.xml` call `PKG_SECURITY.has_permission` to disable buttons/blocks; `PKG_PAYROLL.approve_payroll`, `PKG_LEAVE.approve_leave_request`, `PKG_EMPLOYEE.update_employee` do not check permissions. Row-level filters use `:GLOBAL.current_emp_id` in Forms `WHERE` clauses (`HRMS_LEAVE.xml`), so "my requests" is a UI concern. `has_permission` rules are grade-based (`JOB_TITLES.GRADE_ID`, which changes on promotion) with no manager-of-record or department rule, so `LEAVE/APPROVE` is `TRUE` only for grade >= 8 while the form lets any manager approve from the filtered queue - the UI and the package disagree about who may approve.

**Impact.** A new API that mirrors package signatures without a policy layer exposes approve/terminate/salary to any authenticated user; direct-object-reference bugs (`?empId=`) leak other employees' data.

**Mitigation.** `PermissionPolicy` port with characterization tests against `has_permission` (Phase 0); `@PreAuthorize` on every mutating endpoint; ownership filters derived from the principal, never from parameters; role model replaces grade heuristics with an audited mapping; negative tests per role in every phase (TEST_STRATEGY 3.6).

**Owner / decision.** Security officer + HR for role definitions.

**Detection / exit.** Authorization matrix tests 100% green; DAST scan shows no IDOR findings.

---

## Watch-list (not in top 10, tracked)

- `get_org_chart` performance above 500 employees (`PKG_EMPLOYEE` comment) - materialise hierarchy if confirmed.
- README/production scope larger than repo (18 forms, 8 reports) - re-baseline after R-04 inventory.
- User adoption/training across 3 offices during coexistence; two logins until Phase 6.
- Oracle Forms and WebLogic support lifecycle dates vs plan duration.
- `AUDIT_LOG` growth (`purge_old_records` 365 days) and CLOB JSON parsing when exposing history in the UI.
