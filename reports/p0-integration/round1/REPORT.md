# P0 Foundation – integration test, round 1

Revision: `p0-foundation/integration` @ `9e52a85cfc226eae1209d451f58b7f8b75d2d517` (PR #11)
Mode: GOLDEN-ORACLE OFF – PostgreSQL 16 only; Oracle/utPLSQL/Forms legs marked *untested-live*.

Stack under test: `postgres:16-alpine` (Flyway V1+V2 from empty DB, `tools/fixtures/pg` seed) →
auth-service (`com.acme.hrms.auth.HrmsApplication`, Java 21, `--server.port=8081`) → Vite app shell
(`:3000`) → nginx 1.27 rendered from `proxy/render.py` with the default P0 flags
(AUTH=NEW, everything else LEGACY), listening on `:8080` (`nginx.rendered.conf`).
Seeded login accounts (`seed-accounts.sql`, password `Welcome1!`): executive emp 1, manager emp 21,
staff emp 2/11, staff+must_change_password emp 12.

| Check | Result |
|---|---|
| Level 1 backend `mvn verify` (JUnit + Testcontainers + Spotless) | PASS |
| Level 1 frontend `vitest run` (58 tests), `eslint` (0 errors / 5 warnings), `tsc` | PASS |
| Playwright golden path, real stack (`e2e-real/`, `playwright/index.html`) | **FAIL** 5/9 |
| Level 2 `tools/parallel-run` vs recorded registry (`parallel-run.md`) | **FAIL** 8/10 |
| Level 3 seed counts (`table-counts.csv`) – PG vs Oracle fixture INSERT counts, 10 seeded tables | PASS (Oracle live leg untested-live) |
| Level 3 six PG reconciliation queries vs `tests/golden/views-baseline.csv` | baseline was header-only → **[backend] missing fixture**; populated in this PR (`tests/golden/PROVENANCE-round1.md`), then RECONCILED (`reconcile-report.md`; pre-population diff: `reconcile-empty-baseline.md`) |
| Flyway baseline on empty PostgreSQL | PASS (V1, V2 applied at first start-up) |
| CDC round-trip (`tools/cdc-sync` `HolidaysRoundTripTest`, PG-only) | PASS (unit) – Oracle insert propagation untested-live |
| utPLSQL characterization suites on Oracle | untested-live (no Oracle; `tests/utplsql/` not present) |

## Findings

- [contract] The legacy-tile flow cannot work as specified. `openapi.yaml` says the proxy "passes the
  user's bearer token through" to `POST /legacy/sso/exchange` when the browser follows a `LEGACY`
  module path, but the access token lives only in memory (`frontend/src/api/http.ts`) and a browser
  navigation (`<a href="/employees">`) carries no `Authorization` header – there is no defined carrier
  (cookie, one-time launch token, or JS-initiated launch returning `launchUrl`). Human decision needed
  before backend/frontend can converge. Observed: unauthenticated `GET /employees/` through the proxy →
  raw nginx `401`, the SPA never redirects to `/login` (3 role tests + the legacy-tile test fail).
- [backend] `proxy/module-legacy.conf`: `auth_request /legacy/sso/exchange` is issued with the original
  method (GET) and no JSON body, but the contract/controller require `POST` with
  `{module, clientIp}` → auth-service answers `405`, nginx turns it into `500` for an authenticated
  request (`auth request unexpected status: 405`). Also expects `X-HRMS-Launch-Url` upstream header that
  `SsoController` never emits (it returns `otherparams` in the JSON body). Bridge is unreachable end-to-end.
- [backend] `tools/parallel-run` `ScenarioRegistry` sends `{"module":"EMPLOYEE"}` (upper-case, no
  `clientIp`) for `sso.exchange.legacy-module` / `sso.exchange.new-module-rejected`; the frozen
  `ProxyModule` wire enum is lower-case and `clientIp` is required, so both scenarios fail with
  `VALIDATION_FAILED` – a fixture defect, not an auth-service defect (curl with
  `{"module":"auth","clientIp":"127.0.0.1"}` → 403 `SSO_MODULE_NOT_LEGACY`; `employee` →
  502 `SSO_LEGACY_UNAVAILABLE`, both per contract). `sso.exchange.legacy-module`'s "ok" expectation
  is untested-live without Oracle.
- [backend] Missing fixture: no seeded `user_accounts` / `user_roles` rows exist outside the JUnit
  base class (`tools/fixtures/pg`, Flyway V2 seed roles/permissions only), so "login as seeded users
  of each role" is impossible on a fresh stack; the integration session inserted `seed-accounts.sql`.
- [backend] Missing fixture: `tests/golden/views-baseline.csv` was the header-only skeleton;
  populated here from seed + view definitions (`legacy_source=recorded`).
- [backend] `tools/parallel-run` and `tools/reconcile` READMEs document `java -jar
  target/hrms-tool-*.jar`; the built jars are `*-0.1.0-SNAPSHOT.jar` without a `Main-Class` manifest,
  and `tools/reconcile` resolves `tests/reconciliation/` relative to CWD (must run from repo root).
- [env] (harness, not code) nginx `port_in_redirect` makes `/employees` → `/employees/` redirect to
  the container's listen port; the proxy must be exposed on the same host port (8080) as it listens on.

Passing real-stack scenarios: login for each role with authority-filtered tiles (EXEC/MGR see
`payroll`, STAFF does not; `reports`/`admin` hidden), `-20301` uniform message with ApiError body,
`-20311` policy from the real API and successful change-password round trip, forced set-password for
`must_change_password`, silent refresh on reload via HttpOnly `hrms_refresh`, refresh-token rotation
(replayed cookie → 401 `TOKEN_INVALID`), bad bearer → 401 ApiError, `/legacy/sso/exchange` not
reachable from the browser (404, nginx `internal`). Level 2: all 8 auth scenarios PASS incl. the
SEC-05 lockout divergence (`RATE_LIMITED` vs legacy `-20301`, listed as expected diff); SEC-07/08
covered by `auth.refresh.rotates` and identity-from-JWT in the passing `/api/auth/me` checks.

Verdict: **FAIL** – `failure_owner=contract` (halts for a human); backend findings above are
actionable in parallel.
