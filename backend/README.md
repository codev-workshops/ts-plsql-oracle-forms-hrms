# HRMS backend (Phase 0 – Foundation)

Spring Boot 3 / Java 21 / PostgreSQL 16. Implements `contracts/p0-foundation/` exactly
(`OpenApiContractTest` fails on any undocumented endpoint).

```bash
cd backend
mvn verify            # Level 1: JUnit 5 + Testcontainers PostgreSQL + Spotless check
mvn spotless:apply    # format
```

Modules: `hrms-common` (errors, trace, Flyway migrations, test support), `hrms-validation`
(DTO constraints + `frontend/src/generated/validation-schema.json` exporter), `hrms-audit`
(`error_log`, `audit_log`), `hrms-notification`, `reference`, `salary` (SALARY_RECORDS owner),
`auth` (Spring Security, JWT,
SSO bridge, application entry point). Tooling reactors: `../tools/parallel-run`,
`../tools/reconcile`, `../tools/cdc-sync`.

Runtime configuration (env): `HRMS_PG_URL/USER/PASSWORD`, `HRMS_JWT_PRIVATE_KEY_PEM`,
`HRMS_FIELD_KEY_BASE64` (AES-GCM key), `HRMS_ORACLE_URL/USER/PASSWORD` (optional; SSO exchange
returns `502 SSO_LEGACY_UNAVAILABLE` when absent, per `contracts/p0-foundation/error-codes.md`), `HRMS_PROXY_CIDRS` (callers allowed to hit
`/legacy/sso/exchange`), `HRMS_FLAG_*` (frozen module flags, see `proxy/README.md`).

## Running the PostgreSQL-backed stack locally (golden-oracle mode OFF)

There is no docker compose file; the stack is three manual steps (this is what the integration
session and `frontend/playwright.config.ts` under `E2E_REAL_STACK=1` rely on):

```bash
# 1. PostgreSQL 16
docker run -d --name hrms-pg -e POSTGRES_DB=hrms -e POSTGRES_USER=hrms -e POSTGRES_PASSWORD=hrms \
  -p 5432:5432 postgres:16

# 2. auth-service (runs Flyway V1+V2 on the empty database), JDK 21
(cd backend && mvn -B install -DskipTests)
: "${HRMS_FIELD_KEY_BASE64:?Export a stable base64-encoded 32-byte local key before starting}"
HRMS_PG_URL=jdbc:postgresql://localhost:5432/hrms HRMS_PG_USER=hrms HRMS_PG_PASSWORD=hrms \
HRMS_FIELD_KEY_BASE64="$HRMS_FIELD_KEY_BASE64" HRMS_PROXY_CIDRS=127.0.0.1/32,::1/128 \
java -jar backend/auth/target/auth-0.1.0-SNAPSHOT.jar

# 3. seed fixtures (after Flyway has created the schema)
for f in 01_reference_data 02_employee_data 03_transaction_data 04_user_accounts; do
  docker exec -i hrms-pg psql -v ON_ERROR_STOP=1 -U hrms -d hrms < tools/fixtures/pg/$f.sql
done
```

Seeded logins (`tools/fixtures/pg/04_user_accounts.sql`, password `Welcome1!`):
`david.martinez@company.com` (STAFF), `jennifer.park@company.com` (MANAGER),
`james.richardson@company.com` (EXECUTIVE), `emily.johnson@company.com` (must change password).

Level 2 / Level 3 tool jars (`java -jar`, `lib/` classpath next to the jar):

```bash
(cd tools/parallel-run && mvn -B verify); (cd tools/reconcile && mvn -B verify); (cd tools/cdc-sync && mvn -B verify)
java -jar tools/parallel-run/target/hrms-tool-parallel-run.jar --target http://localhost:8080 \
  --seed-password 'Welcome1!' --report parallel-run.md      # sso.exchange.legacy-module -> UNTESTED-LIVE (P0-D1)
java -jar tools/reconcile/target/hrms-tool-reconcile.jar compare --pg jdbc:postgresql://localhost:5432/hrms \
  --pg-user hrms --pg-password hrms --as-of 2024-06-30 --baseline tests/golden/views-baseline.csv --report reconcile-report.md
java -jar tools/cdc-sync/target/hrms-tool-cdc-sync.jar          # usage; Oracle leg is untested-live
```

## Deliberate divergences from `PKG_SECURITY` (fixed legacy defects)

| Legacy | Target | Rationale |
|---|---|---|
| SEC-01 plain-text / MD5 passwords | BCrypt (`PasswordEncoder`), re-hash on change | contract README |
| SEC-02 distinct "user not found" / "bad password" messages | single `-20301` for every credential failure | user enumeration |
| SEC-05 no lockout | 5 failures per username per 15 min → `429 RATE_LIMITED` (contract) while legacy keeps raising `-20301`; recorded as a documented divergence in `tools/parallel-run` `ScenarioRegistry` | brute force |
| SEC-07 session id from `SEQ_SESSION` | `jti` = random UUID, refresh tokens hashed at rest, rotation with replay revocation | predictable ids |
| SEC-08 identity in Forms `:GLOBAL` / client-supplied `empId` | identity only from JWT claims (`CallerIdentity`) | ARCH-01 |
| `HRMS_LOGIN` 30-char username truncation, `MESSAGE()` side effects | not reproduced; `user_sessions.username` widened to 100 | contract README |

Password complexity (`-20310` min length → `-20311` upper-case → `-20312` digit) and
`PASSWORD_REUSED` follow `PKG_SECURITY` order unchanged.
