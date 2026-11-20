# Modernization Checkpoint

Phase: **2 — DEFINE + PILOT (complete, awaiting gate)**. Next phase: **3 — Execute** (five parallel module slices).

* Phase 1 **ACCEPTED** — the user approved the Phase 1 gate on **2026-11-19** (PR #75, head `23556ce`).
* Working branch for all phases: `devin/1795071118-phase1-discover-inventory` (PR #75 into `main`).
  The user instructed: do **not** merge to `main`/`master`; every phase commits to this branch.

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

## Decisions (Phase 2) — open questions 1–10 resolved (user approved the defaults, 2026-11-19)

| # | Question | Decision | Implemented in Phase 2 |
|---|---|---|---|
| 1 | Authentication | `user_credentials` table with bcrypt hashes; one dev password for all seeded employees; username = employee email | `V1` table, `V2` seed, `platform/auth/*` (login, lockout after `SECURITY.MAX_FAILED_LOGINS`, bearer sessions in `user_sessions`, timeout `SECURITY.SESSION_TIMEOUT_MIN`, logout, password policy) |
| 2 | Phase 4 harness | Phase 2 creates `proxy/` (`/api` → backend, `/` → frontend) + `proxy/test_proxy_config.py`, and a Playwright e2e skeleton gated on `E2E_REAL_STACK=1`, one worker | `proxy/nginx.conf`, `proxy/dev_proxy.py`, `proxy/test_proxy_config.py`; `e2e/` (login + leave specs) |
| 3 | Hire-date future limit | 90 days (-20501) | Employee slice |
| 4 | Salary outside grade range | Warning in the response (`warnings[]`), not blocking | Employee slice (contract `EmployeeDetail.warnings`) |
| 5 | Missing screens/blocks | Leave approvals + team calendar built in the pilot; others built in their Phase 3 module | Leave UI tabs *Approvals*, *Team calendar* |
| 6 | PKG_INTEGRATION | GL journal + benefits feed as downloadable files; time import and org sync remain stubs | Payroll slice (contract `/payroll/runs/{id}/gl-journal`, `/payroll/exports/benefits`) |
| 7 | Tax rules | 2024 constants as data in `tax_brackets`, plus HEAD_OF_HOUSEHOLD brackets | `V2` seed (4 filing statuses); Payroll slice reads them |
| 8 | Permission model | Keep grade-level rules behind `PermissionService` (≥ 8 all, ≥ 5 view all, everyone own Leave + Employee view) | `platform/security/PermissionService` |
| 9 | Holidays | Seed current-year holidays | `V2`: 2026 US holidays (+ SF-only) and 2027-01-01 |
| 10 | Notification delivery | Queue rows in `notification_queue` + logging sender (no SMTP) | `platform/notification/*` |

Additional Phase 2 decisions:

* Backend: Spring Boot 3.3.5, Java 17, Maven, `JdbcTemplate` (no JPA), Flyway; tests use embedded
  Postgres (zonky) so `mvn verify` needs no database or Docker.
* Frontend: React 18 + TypeScript + Vite 5, React Router 6, Vitest + Testing Library, ESLint 9.
* Identity columns start above seeded IDs (no sequence/seed collisions). `employees.email` unique
  (case-insensitive) among active employees. `leave_balances.available` is a generated column.
* Flyway layout: platform + Leave in `db/migration/` (V1–V199); each Phase 3 module in
  `db/migration/<module>/` within its range; enforced by `MigrationLayoutTest`.
* Every legacy `-20xxx` code of every module is pre-registered in `platform/error/ErrorCode`;
  modern-only generic codes are -20990 (not found) / -20991 (conflict); -20000 validation.
* Leave approval: requester's manager or grade ≥ 8, never self (-20205). Missing balance rows are
  initialised, not skipped; auto-approved types go straight to `used`; carryover expiry idempotent.

## Contract v1

* **`docs/modernization/openapi-v1.yaml`** — OpenAPI 3.0.3, 81 paths, tags `platform`, `leave`
  (implemented), `employee`, `payroll`, `performance`, `audit`, `reporting` (to implement in Phase 3).
  Base `/api/v1`, bearer token from `POST /auth/login`, error body `{code, message, details}` with
  legacy codes. Frozen: Phase 3 slices implement it as written; `OpenApiContractTest` fails the
  build if an implemented route is missing from the contract.
* Shared services (read-only for Phase 3): `EmployeeLookup`, `LocationLookup`, `SalaryRecordService`, `AuditService`,
  `NotificationService`, `ParameterService`, `BusinessDayCalendar`, `PermissionService`,
  `Validators`, `SessionService`/`AuthService`, `ErrorCode`/`ApiException`.
* Pre-registered: backend packages `com.hrms.{employee,payroll,performance,audit,reporting}`
  (`package-info.java` states ownership), test packages, migration folders; frontend routes
  `/employees/*`, `/payroll/*`, `/performance/*`, `/audit/*`, `/reports/*` with nav entries
  (`frontend/src/app/modules.ts`) rendering `features/<module>/index.tsx` placeholders.
* How to build a slice: **`docs/modernization/slice-recipe.md`** (owned paths, ranges, checks, DoD).

## Shared change between Phase 3 modules (user-approved, 2026-11-19)

Contract v1 (`openapi-v1.yaml`) is **unchanged**. User decision: **locations: keep numeric
locationId with shared location_id column**.

* `V3__location_ids.sql` (platform): `locations.location_id` BIGINT, NOT NULL, unique; seeded rows
  numbered in `location_code` order (CHI=1, HQ=2, SF=3); new rows get identity values from 100.
  `location_code` stays the primary key; all FKs unchanged. Contract `locationId` = `location_id`.
* `platform.employee.LocationLookup`: `codeForId(locationId)` / `idForCode(locationCode)`.
* `platform.salary.SalaryRecordService.endActive(empId, endDate, user)`: for employee terminate —
  end-dates active `salary_records` and active `employee_pay_elements` (`end_date`, `active_flag='N'`),
  one `AuditService` UPDATE row per changed row; returns rows changed (idempotent).
* Tests: `PlatformServicesTest` (location ids, uniqueness, `endActive`).

## Contract change requests

(none) — Phase 3 slices append here instead of editing shared code or the contract.

## SHAs

* Base SHA (main at start): `de012585455275f35304d53327b6364fdba41712`
* Phase 1 head (inventory + checkpoint content commit): `3d41475f236e0bbc9ddfb764ccf1ef4d8f738d15`; accepted PR head `23556ce31e13194903246cc7700627746c8be69b`.
* Phase 2 base: `23556ce31e13194903246cc7700627746c8be69b`. Phase 2 last code commit:
  `b62cba9` (see `git log`); the only later commit is this checkpoint update. The branch head SHA
  is reported in the Phase 2 hand-off message.

## Evidence

### Phase 1

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

### Phase 2 — Leave pilot evidence (2026-11-19)

| Check | Command | Result |
|---|---|---|
| Backend build + tests | `(cd backend && mvn -B -ntp verify)` | BUILD SUCCESS, 30 tests, 0 failures (LeaveApiTest 11, AuthApiTest 7, PlatformServicesTest 9, OpenApiContractTest 2, MigrationLayoutTest 1) |
| Frontend | `(cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build)` | all exit 0; 5 tests passed (LeavePage 4, App login/nav 1) |
| Proxy | `python3 proxy/test_proxy_config.py` | Ran 8 tests, OK |
| E2E skeleton without stack | `(cd e2e && npx playwright test)` | 3 skipped (expected) |
| E2E real stack | `(cd e2e && E2E_REAL_STACK=1 npx playwright test)` against local Postgres 16 + backend :8080 + Vite :5173 + `dev_proxy.py` :8000 | 3 passed: wrong password rejected; login + nav + logout; employee submits → manager approves → employee cancels |
| Flyway on local Postgres | backend start on an empty `hrms` DB | V1, V2 applied; 30 legacy tables + `user_credentials`, 6 views, 24 employees, 24 credentials |

Leave behaviours covered by `LeaveApiTest` (clock fixed 2026-11-19): submit → PENDING with pending
balance + manager notification; approve moves pending → used; reject releases pending; cancel
releases pending or used, second cancel -20204; auto-approved Jury Duty → used; holidays
(Thanksgiving) excluded, half day = 0.5; error codes -20210, -20211, -20212, -20201, -20203,
-20202, -20000; 5-day backdating allowed; tenure rule; only manager approves, never self (-20205);
requests/balances scoped to caller; team calendar; accrual idempotent per month and capped,
carryover to next year, expiry idempotent, balance initialisation; batch endpoints grade ≥ 8 (-20304);
audit rows per transition.

## Phase 3 slices (next task)

Run one session per module, in parallel (inventory §7.2; fallback §7.3). Each session follows
`docs/modernization/slice-recipe.md` (§4 owned paths, §7 definition of done), commits to the working
branch, and updates **only its own line** below.

| Slice | Legacy source | Owned paths | Flyway | Status |
|---|---|---|---|---|
| Employee | PKG_EMPLOYEE, HRMS_EMPLOYEE, `trg_employees.sql` (inventory §4.7) | `backend/.../com/hrms/employee/`, tests, `db/migration/employee/`, `frontend/src/features/employee/` | V200–V299 | not started |
| Payroll | PKG_PAYROLL, PKG_INTEGRATION, HRMS_PAYROLL (§4.8–4.9) | `.../com/hrms/payroll/`, tests, `db/migration/payroll/`, `frontend/src/features/payroll/` | V300–V399 | not started |
| Performance | PKG_PERFORMANCE, HRMS_PERFORMANCE (§4.10) | `.../com/hrms/performance/`, tests, `db/migration/performance/`, `frontend/src/features/performance/` | V400–V499 | not started |
| Audit | PKG_AUDIT read/purge, audit viewer (§4.5) | `.../com/hrms/audit/`, tests, `db/migration/audit/`, `frontend/src/features/audit/` | V500–V599 | not started |
| Reporting | PKG_REPORTING, views (§4.11) | `.../com/hrms/reporting/`, tests, `db/migration/reporting/`, `frontend/src/features/reporting/` | V600–V699 | not started |

Phase 4 then integrates: full `mvn -B -ntp verify`, frontend lint/typecheck/test/build,
`python3 proxy/test_proxy_config.py`, `E2E_REAL_STACK=1` e2e (one worker), recording of the final flow.

## Open items

* `nginx` is not installed on the dev VM; `proxy/dev_proxy.py` (same routing) was used for the real-stack run.
* Playwright browser system deps were not installed via `--with-deps` (apt lock); `npx playwright install chromium` was sufficient here.
* Maven Central rate-limited (HTTP 429) during setup; a local `~/.m2/settings.xml` mirror was used (not committed).
