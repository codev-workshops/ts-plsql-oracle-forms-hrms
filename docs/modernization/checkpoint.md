# Modernization Checkpoint

Phase: **1 — DISCOVER (complete)**. Next phase: **2 — Define + Pilot**.

## Goal

Modernize the Oracle Forms HRMS in `codev-workshops/ts-plsql-oracle-forms-hrms` into a
Java (Spring Boot) `backend/` + React `frontend/` running on local Postgres (Flyway
migrations), delivered in four phases:

1. **Discover** (this phase): inventory + checkpoint docs only.
2. **Define + Pilot**: backend/frontend skeletons, Postgres + Flyway, `openapi-v1.yaml` for
   all modules, Leave module end to end, `slice-recipe.md`.
3. **Execute**: one session per remaining module — Employee, Payroll, Performance, Audit,
   Reporting — in parallel where `inventory.md` §7.2 allows.
4. **Integrate**: combine, `mvn verify`, frontend checks, `python3 proxy/test_proxy_config.py`,
   e2e with `E2E_REAL_STACK=1`, screen recording.

## Exclusions

* All Oracle Forms tests and the Forms/WebLogic runtime are out of scope (Forms XML is
  read-only reference material).
* Phase 1 adds no application code: this PR contains only
  `docs/modernization/inventory.md` and `docs/modernization/checkpoint.md`.
* README claims (18 forms, 12 packages, 42 tables, PKG_DEPARTMENT, HRMS_REPORTS, Oracle
  Reports, etc.) are not to be trusted; the inventory is based on the files only.

## Decisions (Phase 1)

| # | Decision | Rationale |
|---|---|---|
| 1 | Module map: shared/platform = PKG_COMMON, PKG_SECURITY, PKG_VALIDATION, PKG_NOTIFICATION, PKG_AUDIT write path, HRMS_LOGIN, HRMS_MENU, both PLL libraries, menu; Leave = PKG_LEAVE/HRMS_LEAVE; Employee = PKG_EMPLOYEE/HRMS_EMPLOYEE/employee triggers; Payroll = PKG_PAYROLL + PKG_INTEGRATION/HRMS_PAYROLL; Performance = PKG_PERFORMANCE/HRMS_PERFORMANCE; Audit = PKG_AUDIT read/purge + log viewer; Reporting = PKG_REPORTING + views | Follows real call graph (inventory §2, §6.1) |
| 2 | Phase 2 Flyway baseline creates **all 30 tables, 6 views, sequences and fixed seed** | Removes table-ownership coupling so Phase 3 can be parallel |
| 3 | Phase 2 ships shared `SalaryRecordService` and read-only `EmployeeLookup` | Breaks the only real cross-module code edges (EMP→PAY, SEC→EMP) |
| 4 | After the Leave pilot, Employee, Payroll, Performance, Audit and Reporting are **parallel-safe**, conditional on decisions 2–3 and pre-registered routes/migration ranges (inventory §7.2). Fallback: Payroll before Employee (§7.3). Merge Reporting last in Phase 4 | Inventory §7 |
| 5 | DB triggers are re-implemented as service-layer logic, not Postgres triggers | Testable; legacy triggers are partly broken |
| 6 | Port the *intended* business rule where legacy code crashes or is insecure (inventory §9); keep error codes (-20xxx) as stable API error codes | Faithful behaviour without known defects |
| 7 | Suggested Flyway ranges: platform+Leave V1–V199, Employee V200–V299, Payroll V300–V399, Performance V400–V499, Audit V500–V599, Reporting V600–V699 | Avoid migration-number conflicts in parallel PRs |

## Open questions (for the coordinator / user; defaults in brackets)

1. **Authentication**: legacy `PKG_SECURITY.authenticate` never checks a password and no
   `USER_CREDENTIALS` table exists. How should the new app authenticate? [Default: add a
   `user_credentials` table with bcrypt hashes; seed one dev password for all seeded
   employees; username = employee email.]
2. **Phase 4 harness missing**: `proxy/test_proxy_config.py`, any `proxy/` directory, and any
   e2e suite / `E2E_REAL_STACK` handling **do not exist** in the repo (no tests, build or CI
   at all). Who creates them, and what should `proxy/test_proxy_config.py` verify?
   [Default: Phase 2 creates `proxy/` (dev reverse proxy config routing `/api` → backend,
   `/` → frontend) plus `proxy/test_proxy_config.py`, and a Playwright e2e skeleton that
   runs against the real stack when `E2E_REAL_STACK=1`.]
3. **Hire-date future limit**: form says 90 days, DB trigger says 180. [Default: 90.]
4. **Salary outside grade range**: legacy only warns (debug). Error, warning, or
   warning-with-override? [Default: warning returned in response; not blocking.]
5. **Missing screens/blocks** (Leave approvals + team calendar, Employee dependents/
   emergency contacts/history, Payroll details/payslip, HRMS_REPORTS, HRMS_ADMIN): build
   from package APIs or drop? [Default: build Leave approvals/calendar in the pilot; others
   in their Phase 3 module.]
6. **PKG_INTEGRATION** (GL file, benefits file, time import stub, org sync stub): in scope
   for Payroll, or deferred? [Default: implement GL journal + benefits export as
   downloadable files; leave stubs as stubs.]
7. **Tax rules**: keep hard-coded 2024 federal brackets / flat state rates, or drive from
   `TAX_BRACKETS`? Head-of-household currently yields 0 federal tax. [Default: port 2024
   constants as data in `tax_brackets`; add HoH brackets.]
8. **Permission model**: legacy uses `GRADE_ID` as a level (≥8 full, ≥5 view-all).
   Keep, or introduce roles? [Default: keep grade-level rules behind a `PermissionService`.]
9. **Holidays**: seed only covers 2024. Add current-year holidays to seed? [Default: yes.]
10. **Notification delivery**: SMTP locally? [Default: queue rows only + log sender.]

## SHAs

* Base SHA (main at start): `de012585455275f35304d53327b6364fdba41712`
* Head SHA: see the PR head commit (recorded in the Phase 1 hand-off message to the coordinator).

## Evidence

* Files inventoried (all files in the repo): `README.md`, `.gitignore`,
  `forms/xml-exports/*.xml` (6), `forms/libraries/*.pll.sql` (2),
  `forms/menus/HRMS_MENU.mmb.sql`, `plsql/packages/*.pks|*.pkb` (11 × 2),
  `plsql/triggers/*.sql` (2 files, 6 triggers), `schema/tables/*.sql` (4 files, 30 tables),
  `schema/views/hrms_views.sql` (6 views), `schema/sequences/hrms_sequences.sql` (29),
  `data/seed/*.sql` (2).
* Package call graph derived by grepping `PKG_<NAME>.` references in package bodies,
  triggers and form XML (inventory §6.1).
* Seed/DDL column mismatches verified by comparing INSERT column lists to CREATE TABLE
  definitions (inventory §8).
* No application code was written; no build or tests exist to run.

## Next task (Phase 2 — Define + Pilot)

1. Read `docs/modernization/inventory.md` (especially §2, §4.1–4.6, §5, §7.2, §8, §9).
2. Create `backend/` (Spring Boot, Maven, Java 17+) and `frontend/` (React) skeletons.
3. Flyway V1: all 30 tables, 6 views (Postgres syntax; `WITH RECURSIVE` for org hierarchy),
   sequences starting above seeded IDs; V2: fixed seed (inventory §8) + current-year holidays.
4. Shared services: auth/session (answer to open question 1), permission matrix (§4.2),
   AuditService, NotificationService, ParameterService, BusinessDayCalendar, validators,
   EmployeeLookup, SalaryRecordService; error model mapping legacy -20xxx codes.
5. Write `openapi-v1.yaml` covering every module's endpoints (from §4 signatures) and
   pre-register frontend routes/nav and backend packages for all modules.
6. Implement the Leave pilot end to end (submit, list, balances, cancel, approve/reject,
   team calendar, accrual/carryover service methods) with tests.
7. Write `docs/modernization/slice-recipe.md` and update this checkpoint (decisions,
   resolved open questions, SHAs, next task = Phase 3 module sessions).
