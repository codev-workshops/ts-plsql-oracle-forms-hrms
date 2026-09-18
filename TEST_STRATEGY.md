# Test Strategy

Goal: prove, phase by phase, that the new Spring Boot + Angular application behaves like the Oracle Forms/PL/SQL application where it must, differs only where a decision says so, and does not leak PII while doing it. The legacy system has **no automated tests** (README), so the first job is to build a behavioural baseline of the legacy before changing anything.

## 1. Test levels and tooling

| Level | Purpose | Tooling | Runs where |
|---|---|---|---|
| L0 Legacy characterization | Capture current PL/SQL behaviour as executable expectations | utPLSQL 3 against the HRMS schema; golden files (CSV/JSON) | Oracle 19c masked clone (nightly), Oracle Free 23ai container (CI, packages that do not need `UTL_FILE`/`UTL_SMTP`) |
| L1 Java unit | Domain rules (`BusinessDayCalculator`, `TaxEngine`, `RatingLabel`, `PermissionPolicy`, ledger math) | JUnit 5, AssertJ, jqwik (property-based) | CI, every commit |
| L2 API integration | Controllers -> services -> Oracle, incl. `SimpleJdbcCall` into retained packages, transactions, locking | Spring Boot Test + Testcontainers `gvenzl/oracle-free`, Flyway + seed loaded per class | CI, every commit |
| L3 Contract | API schema stability for the Angular client; error-code mapping | OpenAPI generated from controllers, `openapi-diff` in CI; Pact optional | CI |
| L4 Parity / shadow | New implementation vs legacy on identical inputs | Parity harness (Java) calling both `PKG_*` and Java service, diffing results; shadow-run comparator for payroll | Masked clone, nightly and per release |
| L5 UI end-to-end | Golden paths per module, role-based visibility, dirty-check, conflict dialogs | Playwright (desktop + mobile viewport), axe-core for accessibility | CI (against Testcontainers) and staging |
| L6 Security | AuthN/AuthZ, IDOR, injection, secrets, PII redaction | Authorization matrix tests (L2), OWASP ZAP baseline, gitleaks/trufflehog, log-redaction assertions | CI + pre-release |
| L7 Non-functional | Load (200 concurrent users, 3 regions), org chart at 3x headcount, payroll run time, accrual job time | k6 or Gatling; Oracle AWR compare | Staging, per phase |
| L8 Operational | Rollback rehearsal, scheduler hand-over, monitoring alerts | Runbook drills on staging clone | Before each go/no-go |
| L9 UAT | Business sign-off per phase with scripted scenarios | Test scripts in `docs/uat/<phase>.md`, defect triage board | Staging with masked/synthetic data |

Definition of done for a phase = all levels applicable to that phase green + acceptance criteria in CUTOVER_PLAN met.

## 2. Test data production

### 2.1 Principles

1. **No real PII anywhere outside production.** Fixtures, screenshots, Playwright traces, log samples and PR descriptions use synthetic data only. Enforced by a CI grep for SSN/routing-number patterns and by the API log-redaction filter tests.
2. **Deterministic.** Every generator takes a seed; the same seed yields the same dataset so parity diffs are reproducible.
3. **Layered.** Small hand-written fixtures for unit tests, medium generated datasets for integration/parity, large generated (or masked) datasets for load.
4. **Resettable.** Each L2 test class gets a fresh schema via Flyway `clean` + `migrate` + seed; parity runs on the masked clone use a restore point (`FLASHBACK DATABASE` / Data Pump reimport) before every run.

### 2.2 Sources

| Dataset | How produced | Size | Used by |
|---|---|---|---|
| `seed-base` | The repository's `data/seed/01_reference_data.sql` + `02_employee_data.sql` (3 locations, 10 grades, 26 job titles, 6 leave types incl. auto-approve JURY/BEREAVE and tenure-gated COMP/FMLA, 11 pay elements, 2024 holidays, 24 active + 1 terminated employee with salaries) converted to Flyway repeatable seeds | ~30 employees | L1 fixtures, L2, L5 |
| `gen-medium` | Java generator `hrms-testdata` (Datafaker for names/addresses, seeded RNG) producing SQL/CSV: org tree with configurable depth and fan-out, managers assigned to create legal hierarchies plus **intentional edge rows** (see 2.3), salaries within/outside grade bands, leave balances/requests in every status, review cycles in every status, tax info per filing status, pay elements per calculation type | 500-2,000 employees | L2 parity, L4, L6 matrix, L9 |
| `gen-large` | Same generator scaled | 20k employees, 5 years of pay periods | L7 |
| `masked-prod` (*only if approved by data owner*) | Data Pump export of production -> masking pipeline: names via deterministic dictionary swap, e-mail rewritten to `emp<ID>@example.test`, DOB shifted +/- 180 days keeping age band, SSN/bank/routing replaced by generated valid-format values re-encrypted with the **test** KMS key, phone/address synthesised, `AUDIT_LOG` and `NOTIFICATIONS` bodies truncated/redacted, `USER_SESSIONS.IP_ADDRESS` nulled, free-text `NOTES`/review CLOBs replaced by lorem ipsum of equal length | production-size | L0 baseline of real distributions, L4 payroll shadow, L7 |
| `golden-payroll` | Output of legacy `PKG_PAYROLL.calculate_payroll` on `gen-medium` for 26 biweekly + 12 monthly periods, exported to CSV per run (`PAYROLL_DETAILS` sorted by `EMP_ID, ELEMENT_CODE`) | 38 files | L1 `TaxEngine`, L4 shadow comparator |
| `golden-leave` | Legacy `calculate_business_days` over 10,000 generated date ranges; `run_monthly_accrual` deltas per employee/type for 12 months; carryover/expiry results | CSV | L1, L4 |
| `golden-perms` | `PKG_SECURITY.has_permission` evaluated for every employee x module x action in `gen-medium` | CSV | `PermissionPolicy` characterization |

The generator is committed to the new repo (`hrms-testdata/`) and produces both PL/SQL-loadable SQL and Java fixture JSON so the same dataset feeds L0 and L1/L2.

### 2.3 Edge cases the generator must always include

- Employees: hire date today, today+89/90/91/180/181 (the 90 vs 180 conflict); hire in the future; `TERMINATED` with pending leave and open salary; rehired; manager chain depth 14, 15, 16; manager cycle attempt; department inactive; duplicate e-mail differing in case; names with apostrophes/hyphens (`O'CONNOR`, injection payloads in search fields); missing location (defaults from dept); employee without tax info (defaults); employee with no salary.
- Payroll: salary exactly at wage base crossover within a period; YTD just under/over 200,000 for additional Medicare; each filing status including one not handled by legacy (`HEAD_OF_HOUSEHOLD` -> legacy returns 0 federal tax); taxable below standard deduction (tax 0); allowances 0 and 10; additional withholding; unknown state code; `MONTHLY`/`BIWEEKLY`/`WEEKLY`; mid-period salary change (as-of semantics); percentage deduction with rounding to .005; pay element effective/end dates on period boundaries; run with a deliberately failing employee (null salary) to exercise `ERROR` rows.
- Leave: request spanning weekend only (0 days); range covering global and location-specific holidays; half-day; back-dated 5 and 6 days; overlap at exact boundaries (start = other end); balance exactly equal to requested days; tenure = `MIN_TENURE_DAYS` - 1 and = ; auto-approve type; carryover above `CARRYOVER_MAX`; expiry date today; balances for prior year only (year rollover).
- Performance: ratings 1.0, 1.49, 1.5, 2.5, 3.5, 4.5, 5.0, 0.99, 5.01; review in each status; employee with no manager (not generated for cycle); duplicate generate attempt; goals with progress 0, 1, 99, 100, explicit status override.
- Security: one user per role and per grade 1-10 in each department; user in two departments' history; inactive user; e-mail collision.

### 2.4 Data reset and isolation

- L2: Testcontainers Oracle Free image with a pre-built snapshot (Flyway + `seed-base`) committed as a Docker layer to keep test start-up under 60 s; each test method in a rolled-back transaction where possible, otherwise `@Sql` cleanup.
- L4/L7 on the masked clone: guaranteed restore point before each run; scheduler jobs disabled in the clone; `UTL_FILE` directories point to a scratch path; `UTL_SMTP` ACL points to a MailHog/Mailpit sink.
- Oracle sequences are reset by the seed so generated `EMP_NUMBER`s are deterministic.

## 3. Testing per phase

### 3.1 Phase 0 - Platform and security foundation

| What | How |
|---|---|
| Legacy baseline | utPLSQL suites for every public procedure in `PKG_EMPLOYEE`, `PKG_PAYROLL`, `PKG_LEAVE`, `PKG_PERFORMANCE`, `PKG_SECURITY.has_permission`, `PKG_COMMON` date/format functions; run on masked clone; results become golden files. Compilation check: all objects `VALID` after each deployment (CI query on `user_objects`). |
| Dependency break (`PKG_SALARY_CORE`) | utPLSQL: `create_employee` still creates a salary record; `calculate_employee_pay` unchanged results vs golden; `user_dependencies` cycle query returns 0 rows. |
| Sequence-based `generate_emp_number` | 50 parallel sessions insert -> 50 distinct numbers; format `EMP-000nnn` preserved; continues after current max. |
| `TAX_BRACKETS` seeding | Legacy `calculate_federal_tax` (hard-coded) vs a table-driven PL/SQL copy over 5,000 random taxable amounts per filing status -> identical. |
| Identity | L2: token from test IdP (Keycloak container) -> `/api/me` returns roles; expired token 401; `USER_SESSIONS` row written. |
| `PermissionPolicy` | Characterization against `golden-perms`: 100% match; property test that promotion (grade change) is reflected on next request. |
| Secrets & PII re-encryption | gitleaks in CI; migration job on masked clone: count in = count out, decrypt-compare 100%, old key removed, app boots with Vault-provided key; key rotation drill re-encrypts a sample. |
| Log redaction | Unit tests feeding SSN/bank/e-mail strings through the logging filter; L2 test that a request containing an SSN never appears unmasked in captured logs. |
| Observability | Smoke: trace ids propagate UI -> API -> JDBC; error dashboards fire on injected 500s. |
| Rollback | Redeploy previous package versions from `db/rollback/`; utPLSQL suite still green. |

### 3.2 Phase 1 - Performance reviews

| What | How |
|---|---|
| Domain rules (L1) | State machine table test for cycle and review transitions incl. the new guards; `RatingLabel` boundary values; goal status derivation; weight sum validation if adopted. |
| API (L2) | Each endpoint against Oracle container; duplicate review -> 409; unauthorised reviewer -> 403; CLOB round-trip of 32 KB assessment; notifications enqueued to `NOTIFICATIONS`; `AUDIT_LOG` rows written; optimistic lock conflict -> 409. |
| Parity (L4) | For `gen-medium`: run legacy `generate_reviews_for_cycle` and the Java equivalent on two clones -> same set of `(CYCLE_ID, EMP_ID, REVIEWER_EMP_ID)`; `get_rating_distribution` vs Java endpoint identical percentages. |
| UI (L5) | Playwright golden paths: HR creates/opens cycle -> employee self-assessment -> manager review -> employee acknowledges -> HR closes; goals CRUD; visibility per role; dirty-form guard; mobile viewport. |
| Security (L6) | IDOR: employee A requests review of B -> 404/403; manager sees only direct reports. |
| Coexistence | Forms `HRMS_PERFORMANCE` in query-only mode displays API-written rows correctly (manual/UAT on clone). |
| UAT (L9) | Pilot cycle script with HR; exit on zero P1/P2. |
| Rollback drill | Disable feature flag + re-enable Forms menu item on staging; verify Forms shows in-flight reviews. |

### 3.3 Phase 2 - Leave

| What | How |
|---|---|
| Domain rules (L1) | `BusinessDayCalculator` vs `golden-leave` (10,000 ranges) with documented exceptions (half-day, observed holidays) covered by separate tests; property tests: `days(a,b) + days(b+1,c) == days(a,c)`; ledger invariants (`AVAILABLE` formula; pending+used never negative); overlap predicate truth table; back-dating limit; tenure gate; auto-approve. |
| API (L2) | Submit/approve/reject/cancel per status matrix (invalid transitions -> 409); `mine=true` ignores any `empId` param; approval by non-manager -> 403; balance auto-initialisation; concurrency: two overlapping submits in parallel -> exactly one succeeds (DB-level check or serialisable retry). |
| Batch (L1/L2) | Accrual job on `gen-medium`: deltas equal legacy `run_monthly_accrual` golden; running twice = no change; crash mid-run (kill after N employees) then rerun = same end state; ShedLock prevents two nodes running; carryover and expiry idempotent. |
| Parity (L4) | Read-model parity: `GET /api/leave/balances` vs `VW_LEAVE_SUMMARY` for every employee on the masked clone, daily during dual-read. |
| Integration with legacy | utPLSQL/L2: `PKG_EMPLOYEE.terminate_employee` still cancels pending requests created by the API and balances are released as before. |
| UI (L5) | Submit with live day/balance quote, cancel, manager approval queue, team calendar rendering across a month boundary; mobile. |
| Non-functional (L7) | Accrual for 20k employees within the maintenance window; approvals list < 500 ms p95. |
| Scheduler hand-over drill (L8) | On staging: disable DBMS_SCHEDULER job, run Java job at month end, verify single execution in `LEAVE_ACCRUAL_LOG`; reverse the hand-over. |
| UAT | Employees across 3 locations (holiday differences), managers approving, HR adjusting balances. |

### 3.4 Phase 3 - Employee management (hybrid)

| What | How |
|---|---|
| Facade (L2) | Each `SimpleJdbcCall` into `PKG_EMPLOYEE` asserts the full side-effect set (rows in `EMPLOYEES`, `EMPLOYEE_HISTORY`, `SALARY_RECORDS`, `LEAVE_REQUESTS`, `EMPLOYEE_PAY_ELEMENTS`, `AUDIT_LOG`, `NOTIFICATIONS`) equals a snapshot produced by calling the package directly - proves the facade adds nothing and loses nothing; error code mapping for `-20001..-20005`, `-20501..-20504`, `ORA-00001`. |
| Search (L2/L6) | Java search vs legacy `search_employees` result sets for 200 generated filter combinations; injection payload corpus (sqlmap-style) produces identical filtered results or 400, never extra rows; paging stability. |
| Validation (L1) | Server `Validation` vs `PKG_VALIDATION`/`PKG_COMMON` golden (e-mail, phone, SSN format, salary-in-grade); document and test the intentional e-mail regex fix. |
| Hierarchy (L2/L7) | Org chart endpoint vs `VW_ORG_HIERARCHY` for `gen-medium`; cycle attempts rejected at depth <= 15 and behaviour at depth 16 documented; p95 < 2 s at `gen-large`. |
| Concurrency (L2) | 50 parallel creates -> distinct numbers; Forms-style `FOR UPDATE` held on a row while API updates -> 409 `RECORD_LOCKED` within timeout. |
| PII (L6) | Every employee DTO snapshot-tested for masked SSN/bank; unmasked read requires `SSN_VIEW` and writes an `AUDIT_LOG` row; Playwright screenshots scanned for SSN pattern; export/download endpoints excluded from PII or permission-gated. |
| History/audit reconciliation (L4) | Same change via Forms (on clone) and via API -> identical `EMPLOYEE_HISTORY`/`AUDIT_LOG` shape; duplicate history rows = 0 after the trigger skip for API module. |
| UI (L5) | Search, detail tabs, lifecycle dialogs (transfer/promote/terminate/rehire), dependents & emergency contacts CRUD, salary change within grade band, read-only protected fields, conflict dialog. |
| UAT | HR pilot performs one week of real-shaped scenarios on staging with `masked-prod` or `gen-medium`. |

### 3.5 Phase 4 - Payroll

| What | How |
|---|---|
| Facade (L2) | Period generation for each frequency vs legacy `create_pay_periods` golden; run state machine incl. `-20103`; approve requires `PAYROLL/APPROVE`; reverse sets run and details `REVERSED`; async calculate returns 202 and completes; `FOR UPDATE` prevents Forms and API acting on the same run. |
| Register/payslip (L2) | API CSV byte-identical to `UTL_FILE` output for the same run (compare on clone); payslip YTD equals `get_ytd_earnings`. |
| Tax engine (L1) | `FederalTaxCalculator`, `StateTaxCalculator`, `FicaCalculator`, `MedicareCalculator` vs `golden-payroll` element by element; property tests: monotonic in taxable income, FICA capped at wage base, additional Medicare only above threshold; `BigDecimal` scale/rounding asserted (`HALF_UP`, 2). |
| Shadow run (L4) | For every real (masked clone) and generated run: comparator report per employee per element; tolerance 0; each delta classified `LEGACY_BUG`/`ENGINE_BUG`/`DATA`; blocker unless classified and signed off. Minimum 3 consecutive periods incl. a year boundary if calendar allows (or simulated by resetting tax year in `gen-medium`). |
| Atomicity (L2) | Inject failure at employee N: with the chosen fix, either no `PAYROLL_DETAILS` for the run or a resumable checkpoint that completes to the same result as an uninterrupted run. |
| GL feed (L4/L5 Phase 5 preview) | `generate_gl_journal` output identical for API-approved and Forms-approved runs. |
| Non-functional (L7) | Calculation of 20k employees within SLA; UI polling load. |
| UI (L5) | Period list, create run, calculate with progress, approve (gated), reverse, details with `ERROR` rows, payslip, download. |
| Rollback drill (L8) | Flip `payroll.engine` flag mid-cycle on staging; reverse and recalc an approved run; verify audit trail. |
| UAT | Payroll team runs a full period in parallel with production Forms on the masked clone and signs the reconciliation. |

### 3.6 Phase 5 - Reports, admin, integrations, notifications

| What | How |
|---|---|
| Reports | Each `PKG_REPORTING` procedure vs new endpoint/APEX page: same parameters -> same row counts and totals on `gen-medium` and masked clone; fiscal-year boundaries (Oct 1) covered. |
| Admin CRUD | L2 validation tests (e.g. `TAX_BRACKETS` non-overlapping ranges, `HOLIDAYS` uniqueness per date/location, `LEAVE_TYPES` accrual fields consistent); audit rows; `SYS_ADMIN` only. |
| Notifications | Outbox dispatcher against Mailpit: every `PENDING` row sent once; retry with backoff on SMTP failure; no duplicates after dispatcher restart; templates snapshot-tested. |
| Integrations | GL, benefits, T&A files byte-identical to legacy for the same inputs; SFTP/object-storage transport tested with a fake server; retry semantics; downstream partner acceptance test in their sandbox. |
| Secrets | `SYSTEM_PARAMETERS` contains no credential-like values (CI regex). |

### 3.7 Phase 6 - Decommission

- Monitoring assertion: `USER_SESSIONS.FORM_MODULE IS NOT NULL` count over 30 days = 0 before WebLogic shutdown.
- Full regression (L1-L6) after removing `PKG_SECURITY`, scheduler jobs, `UTL_*` grants: all objects `VALID`, all suites green.
- If `PKG_EMPLOYEE` is ported to Java: the Phase 3 facade side-effect snapshots are reused verbatim as the oracle for the Java implementation.
- Disaster recovery drill: restore database + redeploy new stack from tags.

## 4. Cross-phase quality gates

| Gate | Threshold |
|---|---|
| Unit coverage of `domain` packages | >= 90% line, 100% of state-transition branches |
| Parity suites | 0 unexplained diffs; explained diffs linked to a decision record |
| Authorization matrix | 100% of (role x endpoint) combinations asserted, negative and positive |
| PII scan (logs, responses, screenshots, fixtures) | 0 hits |
| Accessibility (axe) | 0 critical/serious on golden-path pages |
| Performance | API p95 < 500 ms for list endpoints at 200 concurrent users; batch jobs within maintenance window |
| Flaky tests | quarantined within 24 h, fixed within a sprint; no retries in CI config |

## 5. Ownership

| Area | Owner | Reviewer |
|---|---|---|
| utPLSQL baseline, golden files | DBA/PL-SQL developer | Tech lead |
| Java unit/integration/contract | Feature team | Tech lead |
| Parity harness & shadow comparator | Platform engineer | Finance (payroll), HR (leave/employee) |
| Playwright & accessibility | Feature team + QA | Product owner |
| Security tests, masking pipeline | Security engineer | Data owner |
| UAT scripts and sign-off | Business analysts | HR / Payroll managers |
