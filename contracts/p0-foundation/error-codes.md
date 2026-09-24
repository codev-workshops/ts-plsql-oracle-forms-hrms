# P0 Foundation – `ApiError.code` contract

Source of truth for legacy numbers: `COMPONENT_MAPPING.md` §11. This file copies the
Phase 0 subset and adds the triggering rule and the raising component. **§11 was not
modified** – every legacy code below already exists there; the framework codes in §2 are
not `-20xxx` numbers and therefore live only here.

## 1. Legacy codes this module may return

`code` is transmitted as a **string** carrying the Oracle `SQLCODE` verbatim
(`"-20301"`), so Level 2 parallel-run diffs (TEST_STRATEGY.md §2.2) can compare it with
the `ORA-20xxx` the Forms tier produced.

| `code` | HTTP | Raised by (target) | Legacy origin | Triggering rule | `field` |
|---|---|---|---|---|---|
| `-20301` | 401 | `AuthService.login`, `AuthService.changePassword` | `PKG_SECURITY.authenticate` (`PKG_SECURITY.pkb`) | E-mail not found **or** password does not verify **or** employee not `ACTIVE` **or** e-mail ambiguous (SEC-10). One indistinguishable body and timing for all causes. On `PUT /api/auth/password` it means `currentPassword` did not verify. | `null` (login) / `currentPassword` |
| `-20310` | 400 | `PasswordPolicy` (in `hrms-validation`, invoked by `AuthService.changePassword`) | `PKG_SECURITY.change_password` `LENGTH(p_new_password) < 8` | `newPassword.length < SYSTEM_PARAMETERS[SECURITY.PASSWORD_MIN_LENGTH]` (default 8). Evaluated first. | `newPassword` |
| `-20311` | 400 | `PasswordPolicy` | `NOT REGEXP_LIKE(p_new_password, '[A-Z]')` | `newPassword` contains no ASCII upper-case letter. Evaluated second; only if `-20310` did not fire. | `newPassword` |
| `-20312` | 400 | `PasswordPolicy` | `NOT REGEXP_LIKE(p_new_password, '[0-9]')` | `newPassword` contains no digit. Evaluated third; only if `-20310`/`-20311` did not fire. | `newPassword` |
| `-20001` | 404 | `EmployeeLookup` (shared, owned by employee-service; used by `GET /api/auth/me` and the SSO bridge) | `PKG_EMPLOYEE` / `PKG_LEAVE` `NO_DATA_FOUND` → `-20001` | Employee referenced by the JWT `empId` does not exist, or `EMPLOYMENT_STATUS <> 'ACTIVE'` / `ACTIVE_FLAG <> 'Y'`. In Phase 0 this can only happen for an employee terminated after the token was issued. | `null` |

Messages (frozen, English, must match `PKG_SECURITY.pkb` for parallel-run diffing):

| `code` | `message` |
|---|---|
| `-20301` | `Invalid username or password` |
| `-20310` | `Password must be at least 8 characters` (the number is rendered from the parameter) |
| `-20311` | `Password must contain an uppercase letter` |
| `-20312` | `Password must contain a number` |
| `-20001` | `Employee not found or not active` |

## 2. Framework codes (no legacy equivalent)

UPPER_SNAKE_CASE; produced by `GlobalExceptionHandler` for conditions the Forms tier
never signalled with a `RAISE_APPLICATION_ERROR`. Not compared at Level 2.

| `code` | HTTP | Triggering rule |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean Validation (`MethodArgumentNotValidException`, `ConstraintViolationException`) or unsupported query value (`status`, `fields`, `active`, paging). `field` = first violation; `details[]` = all. |
| `PASSWORD_REUSED` | 400 | `newPassword` verifies against the current hash. |
| `TOKEN_INVALID` | 401 | Missing / malformed / expired access token, revoked `jti`, or refresh token missing / expired / rotated / bound to a `CLOSED` session. |
| `FORBIDDEN` | 403 | `@PreAuthorize` denied (`AccessDeniedException`). |
| `SSO_MODULE_NOT_LEGACY` | 403 | `/legacy/sso/exchange` for a module whose proxy flag is `NEW`, or caller outside the proxy CIDR. |
| `RATE_LIMITED` | 429 | > 5 failed logins per username / 15 min. `Retry-After` header set. |
| `SSO_LEGACY_UNAVAILABLE` | 502 | Oracle unreachable or `PKG_SECURITY.authenticate` returned `-1` for a token the API accepts. |
| `INTERNAL_ERROR` | 500 | Anything unmapped. `message` is generic; details only in `error_log` under `traceId`. |

## 3. `@ControllerAdvice` mapping contract (COMPONENT_MAPPING.md §7 `handle_error`)

One handler class, `common/GlobalExceptionHandler` (`@RestControllerAdvice`), is the
**only** place that builds `ApiError`. Controllers and services never return `ApiError`
themselves and never catch `HrmsException` to translate it.

```java
// hrms-common
public class HrmsException extends RuntimeException {
    ErrorCode code;      // enum: value() -> "-20301" | "VALIDATION_FAILED" ..., status() -> HttpStatus
    @Nullable String field;
}
// subclasses: InvalidCredentialsException(-20301), PasswordPolicyException(-20310..-20312, field="newPassword"),
//             EmployeeNotFoundException(-20001), ...

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(HrmsException.class)            -> status = e.code().status(), body = ApiError(e.code().value(), e.getMessage(), e.field(), traceId)
    @ExceptionHandler(MethodArgumentNotValidException.class,
                      ConstraintViolationException.class) -> 400 VALIDATION_FAILED, details[] from every violation
    @ExceptionHandler(AccessDeniedException.class)    -> 403 FORBIDDEN
    @ExceptionHandler(AuthenticationException.class)  -> 401 TOKEN_INVALID
    @ExceptionHandler(Exception.class)                -> 500 INTERNAL_ERROR (message fixed, never e.getMessage())
}
```

Invariants:

1. `traceId` = current W3C trace id (Micrometer); the same value appears in the
   structured log line and in the `error_log` row written by `ErrorLogService.log(...)`
   with `@Transactional(propagation = REQUIRES_NEW)` – the Java equivalent of the
   `PRAGMA AUTONOMOUS_TRANSACTION` in `HRMS_COMMON.pll` `handle_error`.
2. `message` is never `Throwable.getMessage()` for 5xx; it is never an ORA-/SQL text.
3. The `ORA-20xxx` legacy number is stored in `error_log.error_code` **as an integer**
   (`-20301`) so the reconciliation join with Oracle `ERROR_LOG` works; only the wire
   format is a string.
4. The Forms behaviour `handle_error` → `MESSAGE(...)` popup is replaced client-side by
   `useErrorHandler`: `401 TOKEN_INVALID` → one silent `/api/auth/refresh` then `/login`;
   `field != null` → inline field error; otherwise toast of `message`.
5. Any new `-20xxx` code needed by a later phase is first added to `COMPONENT_MAPPING.md`
   §11, then to that phase's `error-codes.md`, then to `ErrorCode`.
