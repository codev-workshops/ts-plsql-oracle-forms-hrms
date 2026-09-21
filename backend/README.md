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
(`error_log`, `audit_log`), `hrms-notification`, `reference`, `auth` (Spring Security, JWT,
SSO bridge, application entry point). Tooling reactors: `../tools/parallel-run`,
`../tools/reconcile`, `../tools/cdc-sync`.

Runtime configuration (env): `HRMS_PG_URL/USER/PASSWORD`, `HRMS_JWT_PRIVATE_KEY_PEM`,
`HRMS_FIELD_KEY_BASE64` (AES-GCM key), `HRMS_ORACLE_URL/USER/PASSWORD` (optional; SSO exchange
returns `503 SSO_LEGACY_UNAVAILABLE` when absent), `HRMS_PROXY_CIDRS` (callers allowed to hit
`/legacy/sso/exchange`), `HRMS_FLAG_*` (frozen module flags, see `proxy/README.md`).

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
