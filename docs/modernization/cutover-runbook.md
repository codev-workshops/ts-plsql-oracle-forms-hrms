# HRMS cutover runbook (P8)

Oracle Forms/PL/SQL HRMS → Java 21 + Spring Boot 3 + PostgreSQL 16 + React. Contract `v1.5`.
Rehearsed by `DataMigrationRehearsalTest` (repository seed as the legacy dataset). Checkpoint: [`checkpoint.md`](checkpoint.md); parity: [`parity-report.md`](parity-report.md).

## 0. Go-live sign-off items (open user decisions — current behaviour is provisional)

Each item must be signed off (or changed) by the business owner before the freeze. Until then the
system runs the listed provisional default.

| ID | Decision needed | Provisional behaviour at go-live |
|---|---|---|
| OQ-14 | Leave summary `available`: net of pending or legacy (excludes pending)? | Report returns `available` (net of pending) **and** `availableBeforePending` (legacy), labelled |
| OQ-15 | First passwords | Production: unusable credential + one-time reset link per ACTIVE employee (§4.6). Dev seed `Hrms#2026` is dev/test only (`db/seed`) |
| OQ-P4-1 | Refuse review/goal writes when the cycle is not OPEN? | Not refused (legacy parity) |
| OQ-P4-2 | Who reads every review? | Employee, reviewer and `PERFORMANCE:VIEW` (grade ≥ 5) |
| OQ-P4-3 | HR override / reviewer reassignment | None |
| OQ-P5-1 | Department maintenance (TRG-011) | No department write API; departments change by controlled SQL only |
| OQ-P5-2 | SSN capture and key management | No SSN write route. **Prerequisite:** choose the key store; re-encrypt `legacy_stage.ssn_holding` (legacy AES key was hard-coded, DEF-11) into `employees.ssn_encrypted`; until then SSNs stay in the restricted stage schema |
| OQ-P6-1 | HEAD_OF_HOUSEHOLD federal brackets | 0 federal tax (legacy parity) |
| OQ-P6-2 | Pretax deductions reduce taxable wages? | No (legacy parity) |
| OQ-P6-3 | Segregation of duties calculate/approve | None; close = `PAYROLL:EDIT`, reverse = `PAYROLL:APPROVE` |
| OQ-P8-1 | Audit retention | Purge job implemented (365 days, legacy) but **off**; enable only with compliance approval |
| OQ-P8-2 | Reset-link lifetime and HR issuing authority | 7 days; `EMPLOYEE:EDIT` (grade ≥ 8) issues links |

## 1. Pre-cutover checks (T-5 days)

1. Build: `mvn -f backend/pom.xml test` and `cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build` green on the release commit.
2. Infrastructure: PostgreSQL 16 database `hrms`; owner role (Flyway) and a login role that is a member of `hrms_app` (`HRMS_APP_ROLE`); the app connects as the login role. Only the owner may read `legacy_stage`.
3. Fresh schema: `flyway migrate` with **`spring.flyway.locations=classpath:db/migration` only** (never `db/seed` — that carries the dev password). Expect 13 versioned migrations: V1–V10, V200, V400, V500.
   - **Flyway ordering:** P4's `V200` sorts below P6's `V400` and P8's `V500`. On a fresh install they apply in order. A database that already applied V400 without V200 needs `flyway.outOfOrder=true` (or a fresh install); none exists today. Never edit an applied migration.
4. Configuration (environment or secret store, never in source):
   - `HRMS_DB_URL`, `HRMS_DB_USER`, `HRMS_DB_PASSWORD`, `HRMS_APP_ROLE`.
   - Mail: `hrms.notifications.sender=smtp`, `hrms.notifications.from`, `spring.mail.host/port/username/password` (+ `spring.mail.properties.mail.smtp.starttls.enable=true`). The default sender only logs; with `sender=smtp` and no `spring.mail.host` the app refuses to start.
   - Reset links: `hrms.auth.password-reset.link-base=https://<hrms-host>/reset-password#token=`, `hrms.auth.password-reset.token-ttl=P7D`.
   - Jobs stay **off** (`hrms.jobs.enabled` unset) until §5.
5. Dry run the export/load (§3) against a production copy into a scratch database; resolve every BLOCKING precheck issue in Oracle; archive the precheck and reconcile output.
6. Communicate: freeze window, the reset-link e-mail, the new URL.

## 2. Freeze (T-0)

1. Announce read-only; disable Forms logins (revoke `CREATE SESSION` from HRMS users / stop WebLogic Forms).
2. Disable the Oracle `DBMS_SCHEDULER` jobs (accrual, notification queue, payroll batch) and let the notification queue drain or note pending rows.
3. Record the last payroll run and accrual month (they become the baseline the new jobs continue from).

## 3. Export, load, reconcile

```bash
# on the target, as the owner role, after flyway migrate (§1.3)
psql -f backend/migration/01_stage.sql
psql -f backend/migration/02_functions.sql
psql -Atc "SELECT legacy_stage.oracle_export_script()" > export.sql     # run in SQL*Plus against HRMS
sqlplus hrms@legacy @export.sql                                         # one CSV per table, header row
psql -Atc "SELECT legacy_stage.copy_script('/data/export')" > copy.psql
psql -f copy.psql                                                        # \copy into legacy_stage.*
psql -c "SELECT legacy_stage.precheck()"                                 # must return 0
psql -c "SELECT severity, check_name, table_name, row_count, detail FROM legacy_stage.migration_issues ORDER BY severity"
psql -c "SELECT legacy_stage.load('America/New_York')"                   # legacy server time zone; one transaction
psql -c "SELECT * FROM legacy_stage.reconcile('America/New_York')"       # every row MATCH (tax_brackets SKIPPED)
```

What the load does (rehearsed):
- Flags `'Y'/'N'` → booleans; NULL flags (and other NOT NULL columns with a default) backfilled (D-01, INFO `FLAG_BACKFILL`).
- Oracle DATE timestamps read in the legacy time zone and stored as UTC instants.
- **P4 unique review key (V200):** duplicate `(cycle, employee, type)` reviews are dropped before the insert, keeping the rated/most advanced one; goals of a dropped review move to the kept one (`legacy_stage.review_dedupe`). Goal progress/weight clamped to 0–100 (WARN).
- `payroll_details.element_id = 0` on ERROR rows → NULL (DEF-P6-2). `tax_brackets` not loaded (V400 is authoritative, DEF-05).
- Legacy ACTIVE sessions loaded as CLOSED (no token). SSN ciphertext → `legacy_stage.ssn_holding` (OQ-P5-2).
- Job history: each legacy accrual month becomes a `leave_accrual_runs` row and each carried-over year a `leave_carryover_runs` row, so the new jobs never accrue or carry a period twice; legacy double accruals stay in the log, unlinked (WARN `PKG-L04`).
- Identities and `seq_emp_number` continue above the migrated ids (D-02).
- BLOCKING (load refuses): duplicate PKs, empty NOT NULL columns, bad flags, FK orphans (including the FKs P1 declared that Oracle only documented), DEF-24 values, two ACTIVE employees sharing an email (OQ-06), duplicate pay periods (DEF-P6-3), cycles ending before they start, non-object audit JSON.
- WARN to follow up: emails the server validator rejects (DEF-04, fix on next edit); weekend holidays without an observed-date row (DEF-02 — add the observed row in `holidays`).

Reconciliation: `reconcile()` compares, per table, the stage row count (minus deduped reviews) and an md5 checksum of every column, transformed by the load rules, against the loaded table. Columns excluded: `employees.ssn_encrypted` (held), `leave_accrual_log.run_id` (re-linked). Archive the output with the precheck report.

## 4. Application start and first logins

1. Start the backend with jobs off and `hrms.cutover.issue-password-resets=true` **once**: every ACTIVE employee gets an unusable credential (`reset_required`) and a one-time link through the outbox (log line `cutover password resets: N issued, M skipped (no email)`). Repeatable: a second start issues nothing. Employees without an email (M) get a link later via `POST /api/v1/auth/password-resets` once an address is recorded.
2. Restart without that property. Deploy the React build (`frontend/dist`) behind the same origin as `/api`.
3. Enable delivery: `hrms.jobs.enabled=true` on **one** instance (the dispatcher uses `SKIP LOCKED`, but the batch jobs must run once). Watch `notification_queue` move PENDING → SENT; FAILED rows retry up to 3 times.
4. New hires and forgotten passwords: HR (`EMPLOYEE:EDIT`) calls `POST /api/v1/auth/password-resets {empId}`; older links stop working.

## 5. Scheduled jobs (all UTC, `hrms.jobs.enabled=true`, one instance)

| Job | Property (default) | Notes |
|---|---|---|
| Monthly accrual | `hrms.jobs.leave-accrual-cron` (`0 0 1 1 * *`) | Idempotent per month; migrated months are already runs |
| Year-end carryover | `hrms.jobs.leave-carryover-cron` (`0 30 0 1 1 *`) | Carries the previous year; idempotent per year (DEF-03) |
| Carryover expiry | `hrms.jobs.leave-carryover-expiry-cron` (`0 45 0 * * *`) | Expires only unused carried days, once (DEF-03); the first run also expires legacy rows past their date |
| Notification dispatch | `hrms.jobs.notification-delay` (`PT5M`) | Needs the SMTP sender for real delivery |
| Payroll batch | `hrms.jobs.payroll-batch-cron` (`0 0 2 * * *`) | **Disabled by default with the rest**; enable after the first manual run is approved |
| Audit purge | `hrms.jobs.audit-purge-enabled` (`false`), `hrms.audit.retention` (`P365D`) | Off until OQ-P8-1 sign-off; the purge audits itself |

Manual equivalents (`LEAVE:ADMIN`): `POST /api/v1/leave/accrual-runs`, `/carryover-runs`, `/carryover-expiry-runs`.

## 6. Post-cutover verification (T+0, before reopening)

1. `reconcile()` all MATCH; spot-check 10 employees, their balances and latest salary in the UI vs the Oracle export.
2. Sign in with a reset link (test account); `GET /api/v1/menu` permissions match the Forms menu for a grade 2, 6 and 8 user.
3. Submit and approve a test leave request; verify the e-mail is delivered and `audit_log` has INSERT + STATUS_CHANGE.
4. Create (do not approve) a payroll run for the next open period, calculate, compare totals with the last Oracle run; reverse it.
5. Reports: leave summary, pending approvals, org hierarchy render.
6. Confirm jobs are scheduled on exactly one instance.

## 7. Rollback

The legacy Oracle database is untouched by the cutover (export is read-only).
- **Before reopening (T+0):** stop the new application, re-enable Forms logins and the Oracle scheduler jobs; discard the PostgreSQL database. No data reconciliation needed.
- **After reopening:** decide within the agreed window (recommended 48 h). Writes made in the new system are in `audit_log` (with actor and time); replay them in Forms manually or by script, then roll back as above. Reset-link e-mails already sent become harmless once the new system is off.
- Keep the Oracle instance read-only for the retention period after sign-off; drop `legacy_stage` only after OQ-P5-2 re-encryption.
