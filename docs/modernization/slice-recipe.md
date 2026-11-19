# Slice recipe (Phase 3 module sessions)

How a fresh session that only has this repository ports one HRMS module (Employee, Payroll,
Performance, Audit or Reporting) onto the Phase 2 platform. The Leave pilot
(`backend/src/main/java/com/hrms/leave/`, `frontend/src/features/leave/`) is the worked example —
copy its structure.

Read first: `checkpoint.md` (decisions, contract v1), `inventory.md` §4 (your package's rules),
§5 (validation drift), §9 (defects not to replicate), §10 (screens), and
`openapi-v1.yaml` (your tag's paths).

## 1. Toolchain and one-time setup

| Tool | Version | Notes |
|---|---|---|
| Java | 17+ | `java -version` |
| Maven | 3.9+ | `mvn -v`. If Maven Central returns HTTP 429, add a mirror in `~/.m2/settings.xml` (e.g. `https://maven-central.storage-download.googleapis.com/maven2/`, `mirrorOf=central`). |
| Node / npm | 20.x / 10.x | `node -v` |
| PostgreSQL | 14+ (tested 16) | Only for running the stack; backend tests use embedded Postgres (no DB or Docker needed). |
| Python | 3.8+ | Proxy check (stdlib only). |

Local database (once):

```bash
# Option A: Docker
docker run -d --name hrms-pg -e POSTGRES_DB=hrms -e POSTGRES_USER=hrms -e POSTGRES_PASSWORD=hrms -p 5432:5432 postgres:16
# Option B: existing Postgres
createuser -P hrms            # password: hrms
createdb -O hrms hrms
```

Flyway creates the schema and seed on backend start. Reset: `DROP DATABASE hrms; CREATE DATABASE hrms OWNER hrms;`.
Override connection with `HRMS_DB_URL`, `HRMS_DB_USER`, `HRMS_DB_PASSWORD` (defaults `jdbc:postgresql://localhost:5432/hrms`, `hrms`, `hrms`).

## 2. Run the stack

```bash
(cd backend && mvn -B -ntp spring-boot:run)          # :8080, runs Flyway
(cd frontend && npm ci && npm run dev)               # :5173, proxies /api -> :8080
python3 proxy/dev_proxy.py                           # :8000, /api -> :8080, / -> :5173 (or nginx -c proxy/nginx.conf)
```

Open http://localhost:8000. Every seeded employee logs in with their email and the dev password
**`Hrms#2026`** (bcrypt in `user_credentials`, dev only). Useful accounts:

| Login | Emp | Grade | Use |
|---|---|---|---|
| `james.richardson@company.com` | 1 (CEO) | 10 | admin: all modules, batch endpoints |
| `jennifer.park@company.com` | 21 | 6 (manager of 22–24) | approvals, team views, view-all |
| `thomas.baker@company.com` | 22 | 4 | plain employee |

## 3. Checks (all must pass before you push)

```bash
(cd backend && mvn -B -ntp verify)                                     # unit + integration + contract + migration-layout tests
(cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build)
python3 proxy/test_proxy_config.py
# real stack (stack from §2 running):
(cd e2e && npm ci && npx playwright install chromium && E2E_REAL_STACK=1 npx playwright test)
```

`backend` integration tests extend `com.hrms.support.IntegrationTest`: one embedded Postgres per JVM,
all Flyway migrations applied, each test rolled back, clock fixed at **Thursday 2026-11-19 09:00**,
helpers `login(email)`, `call(...)`, `callJson(...)`, `read(...)`. Without `E2E_REAL_STACK=1` the
e2e suite skips (that is expected, not a pass). E2E always runs with one worker.

## 4. Ownership — what each slice may touch

A slice edits **only** its own rows below. Everything else (platform code, `com.hrms.leave`,
`frontend/src/app|auth|api`, `App.tsx`, `modules.ts`, `openapi-v1.yaml`, `V1`/`V2`, proxy, e2e
config, these docs) is shared and read-only. If you need a shared change, stop and record it under
"Contract change requests" in `checkpoint.md` instead of editing.

| Module | Backend package | Backend tests | Flyway folder + range | Frontend feature folder | Route (pre-registered) | OpenAPI tag | e2e spec (optional, new file) |
|---|---|---|---|---|---|---|---|
| Employee | `backend/src/main/java/com/hrms/employee/` | `backend/src/test/java/com/hrms/employee/` | `backend/src/main/resources/db/migration/employee/` V200–V299 | `frontend/src/features/employee/` | `/employees/*` | `employee` | `e2e/tests/employee.spec.ts` |
| Payroll | `.../com/hrms/payroll/` | `.../com/hrms/payroll/` | `.../db/migration/payroll/` V300–V399 | `frontend/src/features/payroll/` | `/payroll/*` (nav grade ≥ 5) | `payroll` | `e2e/tests/payroll.spec.ts` |
| Performance | `.../com/hrms/performance/` | `.../com/hrms/performance/` | `.../db/migration/performance/` V400–V499 | `frontend/src/features/performance/` | `/performance/*` | `performance` | `e2e/tests/performance.spec.ts` |
| Audit | `.../com/hrms/audit/` | `.../com/hrms/audit/` | `.../db/migration/audit/` V500–V599 | `frontend/src/features/audit/` | `/audit/*` (nav grade ≥ 8) | `audit` | `e2e/tests/audit.spec.ts` |
| Reporting | `.../com/hrms/reporting/` | `.../com/hrms/reporting/` | `.../db/migration/reporting/` V600–V699 | `frontend/src/features/reporting/` | `/reports/*` (nav grade ≥ 5) | `reporting` | `e2e/tests/reporting.spec.ts` |

Platform + Leave own V1–V199 at the top of `db/migration/`. `MigrationLayoutTest` fails the build if
a migration is in the wrong folder or range or not named `V<n>__lower_snake.sql`.

All 30 legacy tables, 6 views and the fixed seed already exist (V1/V2): a slice normally needs **no
migration**; add one only for indexes, extra seed rows or module-local additions. Never alter a table
owned by another module; never edit V1/V2.

Frontend: the route already renders `<XxxPage />` from `features/<module>/index.tsx` — keep that export
name, replace the placeholder, add sub-routes inside the feature with nested `<Routes>` (the
parent route ends in `/*`), and put API calls in `features/<module>/api.ts` using `api()` from
`src/api/client.ts`. Tests live next to the code as `*.test.tsx` (see `LeavePage.test.tsx`: stub
`fetch`, render the page, assert).

## 5. Shared services to use (do not re-implement)

| Need | Use (`com.hrms.platform...`) |
|---|---|
| Current user in a controller | add a `CurrentUser user` parameter (resolved from the bearer token; `/api/**` is authenticated except `/auth/login`, `/health`) |
| Permission check | `security.PermissionService.require(user, Module.X, Action.Y)` / `hasPermission(grade, module, action)` (grade ≥ 8 all, ≥ 5 view all) |
| Errors | `throw new error.ApiException(ErrorCode.X, "...")` → JSON `{code,message,details}` with the mapped HTTP status. Every legacy code of every module is already in `ErrorCode` (e.g. `EMPLOYEE_DUPLICATE_EMAIL` -20502, `PAYROLL_WRONG_STATUS` -20103, `PERFORMANCE_INVALID_RATING` -20403); generic `NOT_FOUND` -20990 / `CONFLICT` -20991 for modern-only cases |
| Employee reads | `employee.EmployeeLookup` (`findById`, `findActiveByEmail`, `directReports`, `activeEmployees`, `isManagerOf`) — read-only; only the Employee slice writes `employees` |
| Location ids | `employee.LocationLookup` (`codeForId`, `idForCode`) — contract `locationId` is `locations.location_id` (V3: CHI=1, HQ=2, SF=3); FKs still use `location_code` |
| Salary | `salary.SalaryRecordService` (`current`, `history`, `changeSalary`, `endActive` for terminate: end-dates active salary + pay elements, audited) — Employee (hire/promote/rehire/terminate) and Payroll both call this; nobody else writes `salary_records` |
| Audit write | `audit.AuditService.record(table, id, Action, old, new, user)` — replaces all legacy audit triggers; call it in the same transaction |
| Notifications | `notification.NotificationService.queue(empId, subject, body, refType, refId, user)` (log sender, decision 10) |
| Parameters | `param.ParameterService.get/getInt/getDecimal(group, code[, default])` (`system_parameters`) |
| Business days / holidays | `calendar.BusinessDayCalendar` |
| Field validation | `validation.Validators` |
| Clock | inject `java.time.Clock` (tests fix it) — never call `LocalDate.now()` without it |

Use `JdbcTemplate` + SQL with bind parameters (no string-built SQL; inventory §4.7 injection), as in
`leave/LeaveRepository.java`. Use `@Transactional` on service methods that write.

## 6. Steps

1. `git pull` the working branch; run the §3 checks once to see a green baseline.
2. List your endpoints from `openapi-v1.yaml` (your tag) and the rules from `inventory.md` §4.x.
3. Backend: `XxxModels` (records matching the OpenAPI schemas), `XxxRepository`, `XxxService`
   (rules + permission + audit + notifications), `XxxController` (`@RequestMapping("/api/v1/...")`).
   Paths, methods, field names and status codes must match the contract exactly;
   `OpenApiContractTest` fails if you add a route the contract does not declare.
4. Backend tests in your test package extending `IntegrationTest`: happy path per endpoint, every
   legacy error code you raise, permission denials, and each "do not replicate" defect from §9 that
   applies to you.
5. Frontend: page(s) for the module's screens (inventory §10, including missing blocks per decision 5),
   `api.ts`, tests.
6. Optional e2e spec for your main flow (`requireRealStack()` + `login()` from `e2e/tests/helpers.ts`).
7. Run all §3 checks, then commit and push to the working branch (do not merge, no new PR unless told).
8. Report in `checkpoint.md` **only** under your module's line in "Phase 3 slices" (status, head SHA,
   commands run + results, open items). That is the one shared-file edit allowed.

## 7. Definition of done (per slice)

* Every path in the contract with your tag is implemented and reachable through the proxy.
* Legacy rules from `inventory.md` §4.x ported, with the target-design fixes from §9 and checkpoint decisions.
* Writes go through shared services (audit, salary, notifications); no direct writes to tables
  owned by other modules.
* Backend tests cover each endpoint, error code and permission rule; `mvn -B -ntp verify` green.
* Frontend screen(s) replace the placeholder; `lint`, `typecheck`, `test`, `build` green.
* `python3 proxy/test_proxy_config.py` green; existing e2e (login + leave) still green with `E2E_REAL_STACK=1`.
* Only owned paths changed (`git diff --stat <base>..HEAD` shows nothing outside §4 rows + your checkpoint line).
* Commit pushed; checkpoint line updated.

## 8. Module notes

* **Employee** (inventory §4.7, triggers): hire date ≤ today + 90 days (decision 3, -20501); duplicate
  active email -20502; salary outside grade range → `warnings[]`, not an error (decision 4); emp number
  from a sequence, not `MAX()+1`; termination cancels PENDING leave **and** releases
  `leave_balances.pending` (update rows directly in the same transaction — `leave_requests` and
  `leave_balances` are the only Leave tables you may touch, and only for this); rehire is allowed
  (legacy trigger block -20503 dropped); never hard-delete employees (-20504). Builds dependents,
  emergency contacts and history screens.
* **Payroll** (§4.8, §4.9): server-side status guards (calculate only PENDING, approve only
  CALCULATED); taxes from `tax_brackets` incl. HEAD_OF_HOUSEHOLD (decision 7); payslip YTD computed;
  GL journal + benefits feed as downloads, time import / org sync remain stubs (decision 6).
* **Performance** (§4.10): rating 1.0–5.0 and labels; `generate-reviews` idempotent (unique
  `(cycle_id, emp_id)` exists in V1); acknowledge on wrong status → 409.
* **Audit** (§4.5): read/search/purge only — writing stays in shared `AuditService`; viewer is grade ≥ 8.
* **Reporting** (§4.11): JSON `Report` shape plus `text/csv`; read-only; may read any table/view.
