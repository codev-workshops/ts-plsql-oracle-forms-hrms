# Cutover Plan

Strategy: strangler-fig coexistence. Oracle Forms and the new Angular/Spring Boot application run side by side against the **same HRMS schema**; each phase moves one module's users to the new UI and turns the corresponding Forms module read-only, then off. No data migration between databases is needed; the database is the integration point. Rollback in every phase is therefore "re-enable the Forms module", provided the phase respected the rule *writes go only through packages or the new API, never through new direct DML paths that Forms cannot see*.

Phase sizing is expressed in delivery iterations, not calendar time. Gate criteria are binary.

## Why `HRMS_PERFORMANCE` is Phase 1 (lowest-risk form)

| Candidate | Money | Regulated PII | Coupling | Batch jobs | Known logic defects | Users affected on failure | Verdict |
|---|---|---|---|---|---|---|---|
| `HRMS_LOGIN` / `HRMS_MENU` | no | credentials | everything depends on it | no | auth verifies nothing | all | Platform prerequisite, not a pilot |
| `HRMS_PERFORMANCE` | no | review text only | `PKG_NOTIFICATION`, `PKG_AUDIT` | none | weak guards only | employees/managers, low urgency, cycle-driven | **lowest risk** |
| `HRMS_LEAVE` | no (balances) | no | employee read, notifications, scheduler | monthly accrual, carryover | 4 known | all employees daily | second |
| `HRMS_EMPLOYEE` | salary | SSN, DOB, bank, dependents | payroll cycle, triggers | none | injection, race | HR daily | third |
| `HRMS_PAYROLL` | yes | tax info | employee cycle, GL feed | period runs | hard-coded tax, partial commits | finance; monetary | last |

Performance reviews also have a natural cutover window (between review cycles) during which zero in-flight state exists.

---

## Phase 0 - Platform and security foundation (no end-user cutover)

**Deliverables**

1. `hrms-api` Spring Boot skeleton, `hrms-web` Angular skeleton, CI (build, unit, integration against Oracle container, Playwright), Flyway baseline of the current schema.
2. Identity: OIDC integration with the corporate IdP; roles `EMPLOYEE`, `MANAGER`, `HR_ADMIN`, `PAYROLL_ADMIN`, `SYS_ADMIN`; `HRMS_ROLES`/`HRMS_USER_ROLES` tables seeded from the current grade-based `has_permission` rules; `PermissionPolicy` port with characterization tests against `PKG_SECURITY.has_permission` for every employee in the test set.
3. `/api/me`, app shell with navigation (replaces `HRMS_MENU` for new modules; Forms modules launched via existing URL until migrated).
4. Secrets: AES key and SMTP/FTP settings moved to Vault/KMS; **PII re-encryption job** (decrypt with legacy key via `PKG_SECURITY.decrypt_ssn`, re-encrypt with KMS key, write to new columns, verify, swap) - executed here or at the latest before Phase 3.
5. PL/SQL hygiene needed by later phases: break `PKG_EMPLOYEE`<->`PKG_PAYROLL` cycle (RISK R-02), sequence-based `generate_emp_number`, `TAX_BRACKETS` seeding, `LEAVE_ACCRUAL_LOG` unique key. All under utPLSQL tests.
6. Observability: OpenTelemetry, structured logs with PII redaction filter, dashboards for API errors and Oracle session counts.

**Dependencies**: IdP tenant/app registration; Oracle dev/test environments with the HRMS schema (RISK R-04); Vault.

**Rollback**: nothing user-facing changes. PL/SQL changes are deployed as `CREATE OR REPLACE` with the previous package versions kept in `db/rollback/`; PII re-encryption writes to new columns and swaps only after 100% verification, so rollback is "keep using old columns".

**Acceptance criteria**

- All 11 packages compile with no `INVALID` objects after the dependency break; utPLSQL suite green.
- `PermissionPolicy` returns identical results to `PKG_SECURITY.has_permission` for 100% of (employee, module, action) combinations in the test dataset.
- Login via IdP creates a `USER_SESSIONS` row; Forms login continues to work unchanged.
- Zero secrets in `git grep -i "key\|password"` over the new repos and package bodies (CI check).
- Re-encrypted SSN count = source count; sample decrypt/compare = 100% match.

---

## Phase 1 - Performance reviews (pilot)

**Scope**: full rewrite of `HRMS_PERFORMANCE` (cycles, reviews, goals, calibration) in Java/Angular. `PKG_PERFORMANCE` stays deployed but is not called by the new app.

**Dependencies**: Phase 0. Cutover scheduled after a review cycle is `CLOSED` and before the next is created.

**Cutover steps**

1. Deploy API + UI behind feature flag `performance.enabled` to a pilot group (HR + one department).
2. Freeze: set `HRMS_PERFORMANCE` Forms module to query-only (menu item disabled for pilot users; `INSERT/UPDATE/DELETE ALLOWED = false` via a Forms parameter or by revoking DML on the tables from the Forms schema user if separate - *inferred*: the repo does not show the Forms connection user).
3. Run the new cycle in the new UI for pilot users; expand to all users; disable the Forms module for all.
4. Retire `HRMS_PERFORMANCE.fmx` from the WebLogic deployment.

**Rollback**: re-enable the Forms menu item; data written by the new app is in the same tables with the same status vocabulary, so Forms shows it. Only new guards (e.g. cycle close requires `OPEN`) could produce rows Forms would not have produced; none are incompatible. Rollback window: any time until the Forms module is retired.

**Acceptance criteria**

- Every state transition in `PKG_PERFORMANCE` has an equivalent API endpoint with an automated test; the new app enforces stricter guards where noted in COMPONENT_MAPPING.
- Rating label mapping matches legacy for the boundary values 1.0, 1.49, 1.5, 2.5, 3.5, 4.5, 5.0.
- One complete cycle (create -> generate -> self -> manager -> acknowledge -> close) executed by pilot users with zero P1/P2 defects; notifications delivered.
- `AUDIT_LOG` shows entries for every write performed by the API.
- Playwright suite green on both desktop and mobile viewport.

---

## Phase 2 - Leave management

**Scope**: rewrite of `HRMS_LEAVE` and `PKG_LEAVE` responsibilities (requests, approvals, balances, team calendar, monthly accrual, carryover/expiry) in Java. `PKG_LEAVE` remains deployed for the `terminate_employee` cascade until Phase 3.

**Dependencies**: Phase 0; `HOLIDAYS` data loaded for the current and next year for all locations; ShedLock table.

**Cutover steps**

1. Baseline: snapshot `LEAVE_BALANCES`, `LEAVE_REQUESTS`, `LEAVE_ACCRUAL_LOG` (see TEST_STRATEGY parity tests).
2. Dual-read period: new UI in read-only mode for all users for one iteration (balances and requests rendered from the same tables) - confirms read model parity with zero write risk.
3. Switch writes: enable submit/approve/cancel in the new UI; disable the corresponding Forms buttons (`BTN_SUBMIT`, `BTN_CANCEL`, approve/reject) - simplest is to set `HRMS_LEAVE` to query-only.
4. Disable the `DBMS_SCHEDULER` accrual job **after** the first successful Java accrual run has been reconciled against a dry-run of `PKG_LEAVE.run_monthly_accrual` in a cloned schema. Month boundary is the only safe switch point.
5. Retire `HRMS_LEAVE.fmx`.

**Rollback**: re-enable Forms buttons and the scheduler job. Because balances are a ledger with `PENDING/USED` semantics unchanged, Forms can continue from any state. If the Java accrual ran and must be reverted, use `LEAVE_ACCRUAL_LOG` rows written by the job (tagged `CREATED_BY='API_ACCRUAL'`) to subtract exactly once.

**Acceptance criteria**

- Read-model parity: for every employee, `AVAILABLE` per leave type from the API equals `VW_LEAVE_SUMMARY` at snapshot time.
- Business-day calculation matches `PKG_LEAVE.calculate_business_days` for a generated set of 10,000 date ranges across weekends, holidays (global and location-specific) and year boundaries, **except** for the documented intentional fixes (half-day, observed holidays), which are covered by their own tests.
- Java accrual on the cloned schema produces identical `LEAVE_BALANCES` deltas to the PL/SQL job for all active employees; running it twice produces no additional change (idempotency).
- Carryover expiry executed twice changes nothing on the second run.
- Approval authorization: a non-manager, non-HR user cannot approve (403), verified by tests for each role.
- Forms-driven termination still cancels pending requests (integration test through `PKG_EMPLOYEE.terminate_employee`).

---

## Phase 3 - Employee management (hybrid)

**Scope**: new Angular UI and Spring Boot API; **writes delegated to `PKG_EMPLOYEE`** via JDBC (`create/update/transfer/promote/terminate/rehire`); reads via Java SQL and views; `search_employees` replaced by Java; dependents and emergency contacts through new thin repositories; SSN handling through the KMS-encrypted columns from Phase 0.

**Dependencies**: Phase 0 (dependency break, sequence numbers, PII re-encryption, roles); Phase 2 (so leave cancellation on termination can be verified in both paths). Payroll remains on Forms; `create_employee -> create_salary_record` coupling is preserved because payroll logic is still in PL/SQL.

**Cutover steps**

1. Read-only release of employee search/detail/org chart to all users (replaces the most-used query path; no write risk).
2. Enable writes for HR pilot users; Forms `HRMS_EMPLOYEE` stays available to the rest of HR.
3. Reconcile `EMPLOYEE_HISTORY` and `AUDIT_LOG` entries between the two paths for one iteration: API-driven changes must produce the same history rows the DB triggers produce for Forms changes.
4. Set `HRMS_EMPLOYEE` query-only, then retire.

**Rollback**: re-enable Forms. Both paths write the same tables and the DB triggers remain, so history/audit continuity holds. The only one-way change is `generate_emp_number` becoming sequence-based (numbers stay in the same format; no rollback needed).

**Acceptance criteria**

- API contract tests prove each lifecycle endpoint results in exactly the rows `PKG_EMPLOYEE` would have written (compare `EMPLOYEES`, `EMPLOYEE_HISTORY`, `SALARY_RECORDS`, `LEAVE_REQUESTS`, `EMPLOYEE_PAY_ELEMENTS`, `AUDIT_LOG`, `NOTIFICATIONS` before/after).
- Search returns identical result sets to legacy for the seeded queries and passes injection payloads (`' OR '1'='1`, `%'--`) with no effect.
- Concurrent create of 50 employees yields 50 distinct `EMP_NUMBER`s.
- Org chart for the largest department renders < 2 s at 3x current headcount.
- No unmasked SSN/bank account in any API response, log line, or screenshot (automated redaction test).
- Hire-date rule decision (90 vs 180 days) recorded and enforced consistently in API and trigger.

---

## Phase 4 - Payroll (hybrid, then engine switch)

**Scope A (UI/API)**: Angular payroll pages and Spring Boot API wrapping `PKG_PAYROLL` (periods, runs, async calculate, approve with server-side permission check, reverse, payslip, register download). `UTL_FILE` register replaced by API streaming; `PKG_INTEGRATION.generate_gl_journal` still consumes approved runs unchanged.

**Scope B (engine)**: Java `TaxEngine` + `PayrollCalculationService` reading `TAX_BRACKETS`; shadow mode compares its output to `PAYROLL_DETAILS` produced by `PKG_PAYROLL` for every run.

**Dependencies**: Phases 0 and 3; `TAX_BRACKETS` populated for the current tax year; `PKG_PAYROLL.calculate_payroll` made atomic/restartable (Phase 0 hygiene) so a failed run cannot leave partial state.

**Cutover steps**

1. Release read-only payroll pages (runs, details, payslips) to payroll staff.
2. Enable create/calculate/approve/reverse in new UI for one pay period while Forms `HRMS_PAYROLL` remains available as fallback (both call the same package, so only one path may be used per run - enforce with `FOR UPDATE` on `PAYROLL_RUNS`).
3. After 2 consecutive periods with zero incidents, retire `HRMS_PAYROLL.fmx`.
4. Engine: run Java engine in shadow for **at least 3 consecutive pay periods** covering year-boundary/YTD reset if the calendar allows; publish reconciliation report per run.
5. Switch primary: feature flag `payroll.engine=java`; `PKG_PAYROLL.calculate_*` kept for one more period as the shadow (roles reversed), then retired.

**Rollback**:
- Scope A: re-enable Forms module; runs created by the API are ordinary `PAYROLL_RUNS` rows.
- Scope B: flip `payroll.engine=plsql`; if a Java-calculated run was approved with a discrepancy, use `reverse_payroll` (existing, audited) and recalculate with PL/SQL. Never rollback after `PAID` - use a correction run.

**Acceptance criteria**

- Shadow reconciliation: for each run, per-employee `GROSS`, each tax element, each deduction, `NET` match to the cent; run totals match; tolerance 0. Any mismatch is a blocker with a root-cause note (legacy bug vs engine bug).
- Approve endpoint rejects callers without `PAYROLL/APPROVE` (403) and runs not in `CALCULATED` (409).
- Pay register CSV from the API is byte-identical to the `UTL_FILE` output for the same run (header and row order).
- `get_payslip` YTD columns non-zero and equal to `get_ytd_earnings`.
- GL journal generated from an API-approved run is identical to one from a Forms-approved run.
- A forced failure mid-run leaves no `PAYROLL_DETAILS` rows for the run (atomicity) or leaves a resumable checkpoint - per the chosen fix.

---

## Phase 5 - Reports, admin, integrations, notifications

**Scope**: `HRMS_REPORTS`/`HRMS_ADMIN` (*not in repo; functionality inferred from `PKG_REPORTING`, `SYSTEM_PARAMETERS`, reference tables*): APEX or Angular over `VW_*`/`PKG_REPORTING`; admin CRUD for `SYSTEM_PARAMETERS`, `HOLIDAYS`, `LEAVE_TYPES`, `PAY_ELEMENTS`, `TAX_BRACKETS`, roles; `PKG_NOTIFICATION.process_queue` replaced by the Java dispatcher; `PKG_INTEGRATION` file exports moved to the integration module with SFTP/object storage and retries; nightly reporting refresh moved to a scheduled job.

**Dependencies**: Phases 1-4 (all writers migrated so notifications/outbox semantics are single-sourced). Downstream systems (GL, benefits vendor) must confirm file format/transport acceptance.

**Rollback**: per component, re-enable the `DBMS_SCHEDULER` job or Forms module. File formats are unchanged, so downstream consumers are unaffected.

**Acceptance criteria**

- Each `PKG_REPORTING` report reproduced with identical row counts and totals for the same parameters.
- GL and benefits files byte-identical to legacy output for the same period.
- Notification outbox drains with retry; zero notifications sent twice (idempotency key on `NOTIFICATION_ID`).
- No credentials remain in `SYSTEM_PARAMETERS`.

---

## Phase 6 - Decommission and de-risk

1. Retire WebLogic Forms server after 30 days of zero Forms sessions (`USER_SESSIONS.FORM_MODULE` monitoring).
2. Port `PKG_EMPLOYEE` lifecycle logic to Java (optional; only if the team wants to leave PL/SQL) with the same contract tests from Phase 3 as the oracle.
3. Remove `DBMS_SCHEDULER` jobs, `UTL_FILE` directory objects, `UTL_SMTP` grants; drop `PKG_SECURITY` (after confirming no dependents).
4. Archive Forms XML exports and package versions with a final tag.

**Acceptance**: no `INVALID` objects; no Oracle grants to `UTL_*` for the app schema; all traffic on the new stack for one full month including a payroll run and a leave accrual.

---

## Cross-phase controls

- **Change freeze windows**: no cutover in the last 3 business days of a pay period or during an open review cycle.
- **Go/no-go checklist per phase**: acceptance criteria green in CI, rollback rehearsed in the staging clone within the last 5 days, on-call and Forms fallback owner named, communication sent to affected users.
- **Data reconciliation**: nightly job during coexistence compares row counts and checksums for the phase's tables between "expected from API events" and actual; discrepancies page the team.
- **Observed vs inferred**: items marked *inferred* (Forms connection user, `HRMS_REPORTS`/`HRMS_ADMIN` content, production `USER_CREDENTIALS`) must be confirmed against the production environment before Phase 0 exits.
