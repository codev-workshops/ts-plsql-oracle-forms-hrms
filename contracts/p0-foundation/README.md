# P0 Foundation – contract

Backend (`auth-service`, `hrms-common`, `hrms-validation`, the reference-data slice of
`employee-service`/`leave-service`, the SSO bridge) and frontend (`AppShell`, `LoginPage`,
`ReferenceDropdown`, `AuthContext`, `useErrorHandler`) may assume exactly what is frozen in
`openapi.yaml`, `error-codes.md` and `frontend/src/generated/validation-schema.json`: the
eleven operations and their DTO shapes, that the caller's identity is always `jwt.empId`
(no endpoint takes a client-supplied `empId`), that authorities are `MODULE:ACTION` strings
from the `has_permission` truth table and are enforced with the `@PreAuthorize` expression
written in each operation's `x-preauthorize`, that every failure is an `ApiError
{code, message, field?, traceId}` built by the single `GlobalExceptionHandler` with the legacy
`-20xxx` number carried verbatim as a string, that the refresh token travels only in the
`hrms_refresh` HttpOnly cookie, and that the validation rules a React form pre-checks are the
exported JSON (server remains canonical). **Out of contract** – and therefore free to change
without a contract PR – are: JWT signing algorithm/key management, the PostgreSQL DDL of
`user_accounts`/`user_sessions`/`user_roles`/`role_permissions`/`error_log` (Phase 0 owns
it but no other module may touch it, ARCH-01/02), the proxy implementation (only the flag
names/values below are frozen), CDC topology, the golden-oracle utPLSQL suites, the full
employee search/CRUD (`/api/employees` beyond `status=ACTIVE&fields=id,name,jobTitle`, Phase 3),
and any `SYSTEM_PARAMETERS` other than `SECURITY.SESSION_TIMEOUT_MIN` /
`SECURITY.PASSWORD_MIN_LENGTH`. **Legacy behaviours intentionally not reproduced**:
password-less authentication (SEC-05 – passwords are verified, BCrypt/Argon2, no MD5
hashes migrated; every user goes through a forced set-password flow), predictable session
ids (SEC-08 – random `jti`, not `SEQ_USER_SESSION`), `username` truncation to 30 chars
(DATA-04), distinguishable "no such user" vs "wrong password" failures (single `-20301`),
the `PKG_SECURITY.change_password` stub that never persisted the new password, the
`HRMS_LOGIN` form/route (disabled; Forms is only reachable through `/legacy/sso/exchange`),
`MESSAGE()` popups and `:GLOBAL` variables as the identity carrier, and any trigger,
PL/SQL or PL/pgSQL on the target database.

---

## Reverse-proxy module flags (CUTOVER_PLAN.md §2 rule 2)

Frozen names (also the `ProxyModule` enum in `openapi.yaml`) and permitted values.
Rollback for any module = set the flag back to `LEGACY` and run that module's reverse extract.

| Flag | Values | Default at end of P0 | Routes it governs |
|---|---|---|---|
| `auth` | `NEW` | `NEW` | `/api/auth/**`, `/login`; the legacy `HRMS_LOGIN` route is disabled permanently. Not switchable – listed so the vocabulary is complete. |
| `employee` | `LEGACY` \| `NEW_READONLY` \| `NEW` | `LEGACY` | `/employees/**` (UI), `/api/employees/**`. `NEW_READONLY`: React search/detail, writes still to Forms `HRMS_EMPLOYEE`. |
| `payroll` | `LEGACY` \| `NEW` | `LEGACY` | `/payroll/**`, `/api/payroll/**` (UI + API). Flipped together with `payroll.engine=JAVA`. |
| `payroll.engine` | `LEGACY` \| `JAVA` | `LEGACY` | Which engine's result is approved/paid; Java always also runs in shadow (`payroll_details_shadow`). |
| `leave` | `LEGACY` \| `NEW` | `LEGACY` | `/leave/**`, `/api/leave/**`. |
| `performance` | `LEGACY` \| `NEW` | `LEGACY` | `/performance/**`, `/api/performance/**`. |
| `reporting` | `LEGACY` \| `NEW` | `LEGACY` | `/reports/**`, `/api/reports/**` (`VW_*` consumers). |

Rules: values are upper-case exact matches; unknown value → proxy refuses to start; a
`LEGACY` module path triggers `POST /legacy/sso/exchange` with `module=<flag name>` before
forwarding to the Forms servlet; `NEW*` paths are forwarded to Spring Boot with the bearer
token untouched. `GET /api/reference/**` is always served by Spring Boot (reference tables
are read-only for the UI in every phase).

## `validation-schema.json` – exporter output format (TEST_STRATEGY.md §2.1)

Produced by the `hrms-validation` exporter (a build-time Java main / Gradle task
`exportValidationSchema`) from the Bean Validation annotations and `PasswordPolicy` rules;
committed at `frontend/src/generated/validation-schema.json`. Phase 0 ships a
**hand-written** first version (`generatorVersion: "0.0.0-handwritten"`, zero `sourceHash`);
the exporter, when implemented, must emit this exact shape and its first real output must
differ from the hand-written file only in `generatorVersion` and `sourceHash`.

```jsonc
{
  "$schema": "https://hrms.example/schemas/validation-schema/v1",
  "schemaVersion": 1,                    // bump only on breaking shape change
  "generator": "hrms-validation:exporter",
  "generatorVersion": "<semver>",        // hrms-validation artifact version
  "sourceHash": "<sha256 hex>",          // hash of the DTO + policy sources the file was generated from
  "module": "p0-foundation",             // one file per phase slug; later phases add files or DTOs (additive)
  "parameters": { "<GROUP.NAME>": <value> },   // SYSTEM_PARAMETERS the rules depend on, resolved at export
  "dtos": {
    "<DtoSimpleName>": {
      "fields": {
        "<jsonPropertyName>": {
          "type": "string|integer|decimal|boolean|date|enum",
          "required": true|false,             // @NotNull / @NotBlank
          "trim": true|false,                 // blank→null normalisation applies (strings only)
          "minLength"?, "maxLength"?,        // @Size
          "pattern"?,                         // @Pattern (Java regex, no anchors added)
          "format"?: "email|date|...",       // @Email etc.
          "min"?, "max"?,                     // @Min/@Max/@DecimalMin/@DecimalMax (numbers as JSON numbers or decimal strings)
          "values"?: [...],                   // enum only
          "rules"?: [ {                      // ordered custom constraints; first failure wins
            "id": "<group>.<name>", "kind": "minLength|maxLength|pattern|min|max|custom",
            "value": <any>, "parameter"?: "<GROUP.NAME>",
            "errorCode": "-20xxx|FRAMEWORK_CODE", "message": "<frozen text>" } ],
          "messages": { "required"?, "minLength"?, "maxLength"?, "pattern"?, "format"?, "min"?, "max"? }
        } } } }
}
```

Determinism: keys are emitted in **source declaration order** for `dtos`/`fields`/`rules`
and sorted for `parameters`/`messages`; 2-space indentation, `\n` line endings, trailing
newline. `messages` is always present (may be `{}`).

### Snapshot-test contract

`frontend/src/generated/__tests__/validation-schema.test.ts` (Vitest) is the VAL-03 guard:

1. `toMatchSnapshot()` of the whole file – the snapshot
   (`__snapshots__/validation-schema.test.ts.snap`) is committed; any change to a rule value
   fails CI until `vitest -u` is run **in the same PR** as the backend annotation change, so
   the reviewer sees both tiers move together.
2. Envelope check – `schemaVersion === 1`, generator name, semver, 64-hex `sourceHash`,
   `module`.
3. Vocabulary check – only the field `type`s / rule `kind`s above, DTO names `PascalCase`,
   field names `camelCase`, `errorCode` matches `^(-20[0-9]{3}|[A-Z][A-Z0-9_]{2,63})$`.
4. Password-policy pin – `ChangePasswordRequest.newPassword.rules` is exactly
   `["-20310","-20311","-20312"]` in that order and its `minLength` equals
   `parameters["SECURITY.PASSWORD_MIN_LENGTH"]`.

Backend side (Level 1): `hrms-validation` has a mirror test that runs the exporter into a
temp file and asserts it is byte-identical to the committed
`frontend/src/generated/validation-schema.json`. Both tests are Level 1 gate items for row 0
of TEST_STRATEGY.md §5; neither may be skipped or loosened. The frontend session that
scaffolds the React app must wire `vitest` so this test runs in CI (the test file assumes
`resolveJsonModule`).
