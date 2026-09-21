# P0 – Foundation: integration-test report, round 2

* Integration branch: `p0-foundation/integration` @ `c0c965ab04e9b0a4b109e07acb01dfbf4849df2d`
* Mode: golden-oracle **OFF** (no Oracle / Forms / utPLSQL; PostgreSQL 16 in Docker, auth-service boot jar, Vite real-stack frontend)
* Run date: 2026-09-21 (UTC)

## Verdict

| Check | Result | Evidence |
|---|---|---|
| Level 1 – backend JUnit (`mvn verify`, 5 modules, 39 tests) + tool reactors (parallel-run 7, reconcile 14, cdc-sync 5) | **PASS** | `backend-verify.log`, `*-verify.log` |
| Level 1 – frontend Vitest (59) + typecheck + lint (0 errors, 5 pre-existing warnings) | **PASS** | `frontend-vitest.log`, `frontend-typecheck.log`, `frontend-lint.log` |
| Level 1 – proxy config tests (`proxy/test_proxy_config.py`, 5) | **PASS** | `proxy-tests.log` |
| Level 2 – `tools/parallel-run` vs PostgreSQL-backed auth-service | **FAIL** (exit 1, 9/10) – `sso.exchange.legacy-module` reported `TARGET-DIFF` | `parallel-run.md`, `parallel-run.log` |
| Level 3 – seed counts, 30 legacy tables (Oracle fixture insert count vs PostgreSQL `count(*)`) | **PASS** (30/30 identical) | `seed-counts-diff.csv`, `pg-table-counts.csv`, `oracle-fixture-counts.csv` |
| Level 3 – six PostgreSQL reconciliation queries vs `tests/golden/views-baseline.csv` | **PASS** (`RECONCILED`, 0 mismatching cells; 23/23/23/11/4/5 rows) | `reconcile-report.md`, `reconcile.log` |
| Playwright golden path – committed `frontend/e2e/golden-path.spec.ts`, real stack | **FAIL** (1 pass / 3 fail / 1 skipped per P0-D1) | `playwright-committed.log`, `playwright-committed-report/` |
| Playwright golden path – same spec with the committed seed accounts substituted (harness copy, not in repo) | PASS (6/6, incl. manager tile + reload/refresh recovery) | `playwright-adapted.log`, `playwright-adapted-report/`, `harness-adapted.spec.ts` |
| API 401/refresh flow (garbage bearer → 401 `TOKEN_INVALID`; refresh cookie → 200 rotated token) | PASS | `refresh-flow.txt`, `login-smoke.txt` |
| Phase-specific – Flyway V1/V2 apply on empty PostgreSQL | **PASS** | `auth-service.log` (`Successfully applied 2 migrations`) |
| Phase-specific – utPLSQL suites on Oracle | `untested-live` (no Oracle, P0-D1 / mode OFF; not a failure) | – |
| Phase-specific – CDC round-trip | `untested-live` for the Oracle leg; PostgreSQL-only `HolidaysRoundTripTest` 5/5 PASS | `cdc-sync-tests.txt`, `cdc-sync-verify.log` |
| Legacy-tile SSO e2e | excluded from the gate (DECISION P0-D1); spec is `test.skip` with the P0-D1 comment | – |

**Gate verdict: FAIL – failure_owner = both.**

## Findings (routed)

1. `[frontend]` `frontend/e2e/golden-path.spec.ts` logs in with the msw-only accounts
   `staff@hrms.example` / `admin@hrms.example` / `newhire@hrms.example`, password `Welcome1`, and asserts the
   msw display names (`Sam Staff`, `Ada Admin`). The committed real-stack fixture
   (`tools/fixtures/pg/04_user_accounts.sql`, asserted by the backend `SeededAccountsTest`) seeds
   `david.martinez@company.com` (STAFF), `jennifer.park@company.com` (MANAGER), `james.richardson@company.com`
   (EXECUTIVE), `emily.johnson@company.com` (must_change_password) with password `Welcome1!` and display name
   `FIRST_NAME LAST_NAME` (upper-case seed). Under `E2E_REAL_STACK=1` all three login-dependent tests fail with
   HTTP 401 `-20301` (UI correctly shows "Invalid username or password"). Fix: parametrise the spec (or add an
   `E2E_REAL_STACK` account map) to use the committed seed accounts; the msw handlers may keep their own users.
   The adapted run proves the UI itself behaves per contract once the accounts match.
2. `[backend]` `tools/parallel-run` `ScenarioRegistry`: `sso.exchange.legacy-module` is marked `untested-live`
   on the legacy side only; the target side still expects `ok {formsModule=HRMS_EMPLOYEE}` and so records
   `TARGET-DIFF` / exit 1 when Oracle is absent (auth-service correctly answers `SSO_LEGACY_UNAVAILABLE`, which
   is the contracted no-Oracle response). Per P0-D1 the whole end-to-end SSO exchange is `untested-live`: the
   harness must report the scenario as `UNTESTED-LIVE` (not a verdict-affecting diff) when run without
   `--oracle`, or accept `SSO_LEGACY_UNAVAILABLE` as the expected target outcome in that mode. Until then
   Level 2 cannot exit 0 in golden-oracle-OFF mode.
3. `[backend]` `tools/cdc-sync` jar (`hrms-tool-cdc-sync-0.1.0-SNAPSHOT.jar`) has no `Main-Class` manifest and
   is not an executable tool jar (`no main manifest attribute`), unlike `hrms-tool-parallel-run.jar` /
   `hrms-tool-reconcile.jar` which were fixed in round 1. `CdcSyncMain` runs only with a hand-assembled
   classpath. Low severity; `untested-live` items are unit-tested.
4. `[backend]` docs: `backend/README.md` / `frontend/playwright.config.ts` reference a `backend/auth docker compose`
   for the integration stack; no compose file exists in the tree. The stack was started manually
   (postgres:16 container + boot jar + fixtures via psql). Low severity, documentation only.
5. `[backend]` `error-codes.md` says `SSO_LEGACY_UNAVAILABLE` → 502 while `backend/README.md` says 503; the
   service returns 502 (matches the frozen contract). Documentation nit only.

No `[contract]` or `[env]` findings: the frozen OpenAPI/error-code contract was honoured by every observed
response (`-20301`, `-20310/11/12`, `RATE_LIMITED` 429 + `Retry-After`, `TOKEN_INVALID` 401, `SSO_MODULE_NOT_LEGACY`,
`SSO_LEGACY_UNAVAILABLE` 502).

## How the stack was run

```bash
docker run -d --name hrms-pg -e POSTGRES_DB=hrms -e POSTGRES_USER=hrms -e POSTGRES_PASSWORD=hrms -p 5432:5432 postgres:16
(cd backend && mvn -B verify && mvn -B install -DskipTests)            # JDK 21
(cd tools/parallel-run && mvn -B verify); (cd tools/reconcile && mvn -B verify); (cd tools/cdc-sync && mvn -B verify)
HRMS_PROXY_CIDRS=127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12 java -jar backend/auth/target/auth-0.1.0-SNAPSHOT.jar   # Flyway V1+V2 on empty DB
for f in 01_reference_data 02_employee_data 03_transaction_data 04_user_accounts; do
  docker exec -i hrms-pg psql -v ON_ERROR_STOP=1 -U hrms -d hrms < tools/fixtures/pg/$f.sql; done
java -jar tools/parallel-run/target/hrms-tool-parallel-run.jar --target http://localhost:8080 --seed-password 'Welcome1!' --report parallel-run.md
java -jar tools/reconcile/target/hrms-tool-reconcile.jar compare --pg jdbc:postgresql://localhost:5432/hrms --pg-user hrms --pg-password hrms \
     --as-of 2024-06-30 --baseline tests/golden/views-baseline.csv --report reconcile-report.md
(cd frontend && npm ci && npx playwright install --with-deps chromium && E2E_REAL_STACK=1 npx playwright test --reporter=html,line)
```

Seed-count method (no Oracle): `oracle_fixture_inserts` = number of `INSERT INTO <table>` statements in
`tools/fixtures/oracle/*.sql` (generated from the same `data/seed` as the PG fixtures); compared against
`select count(*)` on PostgreSQL for each of the 30 `CREATE TABLE`s in `schema/tables/`. Tables without seed rows are
0 on both sides.
