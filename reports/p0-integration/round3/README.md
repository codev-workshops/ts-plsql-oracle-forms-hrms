# P0 – Foundation: integration-test report (post-remediation run)

* Integration branch: `p0-foundation/integration` @ `ea5925e4d8030755e53985d7d7e5b9de511fadce`
* Mode: golden-oracle **OFF** (no Oracle / Forms / utPLSQL; PostgreSQL 16 in Docker, auth-service boot jar,
  Vite real-stack frontend with `E2E_REAL_STACK=1`)
* Run date: 2026-09-22 (UTC)

## Verdict

| Check | Result | Evidence |
|---|---|---|
| Level 1 – backend JUnit (`mvn -B verify`, 5 modules: common 15, validation 5, audit 2, notification 2, auth 17 = 41) + tool reactors (parallel-run 9, reconcile 14, cdc-sync 6) + Spotless | **PASS** (`BUILD SUCCESS`) | `backend-verify.log` |
| Level 1 – frontend Vitest (15 files / 64 tests) + `tsc -b --noEmit` + `eslint .` | **PASS** | `frontend-vitest.log`, `frontend-typecheck.log`, `frontend-lint.log` |
| Level 1 – proxy config tests (`proxy/test_proxy_config.py`, 5) | **PASS** | `proxy-tests.log` |
| Playwright golden path – committed `frontend/e2e/golden-path.spec.ts`, real stack | **PASS** (5 passed, 1 skipped = legacy-tile SSO per P0-D1) | `playwright-committed.log`, `playwright-committed-report/index.html` |
| Playwright – ad-hoc harness spec (not in repo): tiles == authorities for STAFF/MANAGER/EXECUTIVE, reload restores session via HttpOnly refresh cookie, 401 → one silent `POST /api/auth/refresh` → replay (`401` then `400 PASSWORD_REUSED`), lost cookie → `/login` | **PASS** (6/6) | `harness-adhoc-integration.spec.ts`, `playwright-adhoc-report/index.html` |
| Level 2 – `tools/parallel-run` vs PostgreSQL-backed auth-service (`legacy_source=recorded`) | **PASS** (exit 0; 9 PASS, 1 `UNTESTED-LIVE` = `sso.exchange.legacy-module`, P0-D1). Only expected diff: `auth.lockout.after-5-failures` legacy `-20301` vs target `RATE_LIMITED` (documented SEC-05 divergence) | `parallel-run.md` |
| Level 3 – seed counts, 30 legacy tables (Oracle fixture INSERT count vs PostgreSQL `count(*)` after `tools/fixtures/pg/*.sql`) | **PASS** (30/30 identical; `audit_log`/`user_sessions` seed 0 = 0, extra rows are runtime writes of the auth-service during L1/e2e/L2) | `seed-counts.md`, `seed-counts.py` |
| Level 3 – six PostgreSQL reconciliation queries vs `tests/golden/views-baseline.csv` (`--as-of 2024-06-30`) | **PASS** (`RECONCILED`, 0 mismatching cells; 23/23/23/11/4/5 rows) | `reconcile-report.md` |
| Phase-specific – Flyway V1/V2 apply on empty PostgreSQL | **PASS** (`Successfully applied 2 migrations`; also `FlywayBaselineTest` 8/8 in L1) | `auth-service-flyway.log` |
| Phase-specific – utPLSQL characterization suites on Oracle | `untested-live` (no Oracle, mode OFF; suites present under `tests/utplsql/`; not a failure) | – |
| Phase-specific – CDC round-trip (insert on Oracle appears on PostgreSQL) | `untested-live` for the Oracle leg; PostgreSQL-only `HolidaysRoundTripTest` 5/5 + `ExecutableJarManifestTest` PASS; `hrms-tool-cdc-sync.jar` is now executable (prints usage) | `backend-verify.log` |
| Legacy-tile SSO e2e | excluded from the gate (DECISION P0-D1); spec is `test.skip` with the P0-D1 comment; tile rendered disabled with the "Not available in this environment" hint (asserted by the committed spec) | – |

**Gate verdict: PASS – failure_owner = none.**

Round-2 findings re-checked on this tree: seed accounts in `e2e/seed-accounts.ts` match `04_user_accounts.sql` (fixed);
`sso.exchange.legacy-module` now `UNTESTED-LIVE` / exit 0 (fixed); cdc-sync jar has a `Main-Class` (fixed);
`backend/README.md` now documents the manual three-step stack and 502 for `SSO_LEGACY_UNAVAILABLE` (fixed).

## Non-gating notes

* `[frontend]` (advisory) the committed real-stack spec does not exercise the 401 → silent refresh → replay flow
  end-to-end (it is covered by Vitest `src/api/__tests__/http.test.ts` and was verified here with the ad-hoc
  harness spec). Consider folding `harness-adhoc-integration.spec.ts` (tiles-per-role, reload, 401/refresh,
  lost-cookie cases) into `frontend/e2e/` so the gate's "401/refresh flow" is asserted by the repo itself.
* The change-password golden-path test mutates the EXECUTIVE seed password (`Stronger9!`); the harness reset the
  BCrypt hash before Level 2 (`ScenarioRegistry` logs in as `sarah.chen`, so L2 is not affected either way).

## How the stack was run

```bash
docker run -d --name hrms-pg -e POSTGRES_DB=hrms -e POSTGRES_USER=hrms -e POSTGRES_PASSWORD=hrms -p 5432:5432 postgres:16
(cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B verify)          # Level 1 (Testcontainers postgres:16-alpine)
HRMS_PG_URL=jdbc:postgresql://localhost:5432/hrms HRMS_PG_USER=hrms HRMS_PG_PASSWORD=hrms \
HRMS_PROXY_CIDRS=127.0.0.1/32,::1/128 java -jar backend/auth/target/auth-0.1.0-SNAPSHOT.jar   # Flyway V1+V2
for f in 01_reference_data 02_employee_data 03_transaction_data 04_user_accounts; do
  docker exec -i hrms-pg psql -v ON_ERROR_STOP=1 -U hrms -d hrms < tools/fixtures/pg/$f.sql; done
(cd frontend && npm ci && npm run typecheck && npm run lint && npm test && E2E_REAL_STACK=1 npx playwright test)
java -jar tools/parallel-run/target/hrms-tool-parallel-run.jar --target http://localhost:8080 --seed-password 'Welcome1!' --report parallel-run.md
java -jar tools/reconcile/target/hrms-tool-reconcile.jar compare --pg jdbc:postgresql://localhost:5432/hrms --pg-user hrms \
  --pg-password hrms --as-of 2024-06-30 --baseline tests/golden/views-baseline.csv --report reconcile-report.md
python3 seed-counts.py                                                                # 30-table seed count comparison
```

Docker Hub returned 429 for `postgres:16`; the image was pulled from `mirror.gcr.io/library/postgres:16(-alpine)` and
re-tagged. Environment only – not a finding against the code under test.
