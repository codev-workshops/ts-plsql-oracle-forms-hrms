# P1 Performance – `ApiError.code` contract

Source of truth for legacy numbers: `COMPONENT_MAPPING.md` §11. This file copies the
Phase 1 subset (`-20401 … -20403`, `-20001`) and adds the HTTP status, the triggering rule
and the raising component. **§11 was not modified** – every legacy code below already
exists there. The framework codes in §2 are not `-20xxx` numbers and therefore live only
here (and in `ErrorCode`).

Everything in `contracts/p0-foundation/error-codes.md` §2 (`VALIDATION_FAILED`,
`TOKEN_INVALID`, `FORBIDDEN`, `INTERNAL_ERROR`, …) and §3 (`GlobalExceptionHandler`
mapping contract, `error_log` invariants) applies unchanged to `/api/performance/**`.

## 1. Legacy codes this module may return

`code` is transmitted as a **string** carrying the Oracle `SQLCODE` verbatim (`"-20402"`),
so Level 2 parallel-run diffs (TEST_STRATEGY.md §2.2, `legacy_source=recorded`) compare it
with the `ORA-20xxx` the package would have raised.

| `code` | HTTP | Raised by (target) | Legacy origin (`PKG_PERFORMANCE.pkb`) | Triggering rule | `field` |
|---|---|---|---|---|---|
| `-20401` | 422 | `ReviewCycleService.open`, `.close`, `.generateReviews`, `.update` | `open_review_cycle`: `UPDATE … WHERE STATUS='DRAFT'` → `SQL%ROWCOUNT = 0` | Cycle exists but its `status` does not permit the operation: `open` requires `DRAFT`; `close` requires `OPEN`/`IN_PROGRESS`/`CALIBRATION`; `generate-reviews` requires `DRAFT`/`OPEN`; `PUT` requires `DRAFT`. (Legacy raised it only for `open`; the other three guards are target additions using the same code – see §4.) | `null` |
| `-20402` | 422 | `PerformanceReviewService.submitSelfAssessment`, `.submitManagerReview`, `.acknowledge`; `GoalService.add`, `.updateProgress` | `submit_self_assessment`: `UPDATE … WHERE STATUS IN ('NOT_STARTED','SELF_REVIEW')` → `SQL%ROWCOUNT = 0` | Review exists but its `status` is not a valid source state for the transition (`ReviewStatus` state machine in `openapi.yaml`): self-assessment from `NOT_STARTED`/`SELF_REVIEW`; manager review from `MANAGER_REVIEW`/`MEETING_SCHEDULED`; acknowledge from `COMPLETED`; add goal while not `COMPLETED`/`ACKNOWLEDGED`; goal progress while not `ACKNOWLEDGED`. | `null` |
| `-20403` | 400 | `PerformanceReviewService.submitManagerReview` (first check, before the status guard) | `submit_manager_review`: `IF p_overall_rating < 1.0 OR p_overall_rating > 5.0` | `overallRating < 1.0` or `> 5.0`. A non-numeric or missing rating is Bean Validation (`VALIDATION_FAILED`), not `-20403`. | `overallRating` |
| `-20001` | 404 | `EmployeeLookup` (shared, owned by employee-service; used to resolve `jwt.empId`, reviewee and reviewer names) | `PKG_EMPLOYEE` / `PKG_LEAVE` `NO_DATA_FOUND` → `-20001` | Employee referenced by the JWT `empId` does not exist or is not `ACTIVE`/`ACTIVE_FLAG='Y'` (terminated after the token was issued). Never raised for the reviewee/reviewer of a stored review – terminated employees' reviews stay readable. | `null` |

Messages (frozen, English; `-20402`/`-20403` match `PKG_PERFORMANCE.pkb` verbatim,
`-20401` is verbatim for `open` and follows the same pattern for the added guards):

| `code` | operation | `message` |
|---|---|---|
| `-20401` | `open` | `Cannot open cycle - must be in DRAFT status` |
| `-20401` | `close` | `Cannot close cycle - must be OPEN, IN_PROGRESS or CALIBRATION` |
| `-20401` | `generate-reviews` | `Cannot generate reviews - cycle must be DRAFT or OPEN` |
| `-20401` | `PUT` | `Cannot edit cycle - must be in DRAFT status` |
| `-20402` | all | `Review not found or not in correct status` |
| `-20403` | manager-review | `Rating must be between 1.0 and 5.0` |
| `-20001` | all | `Employee not found or not active` |

## 2. Framework codes added by this phase (no legacy equivalent)

UPPER_SNAKE_CASE; produced by `GlobalExceptionHandler` from `HrmsException` subclasses.
Not compared at Level 2 (the legacy package signalled these situations with `-20401`,
`-20402` or with a silent `0 rows updated` – see §4).

| `code` | HTTP | Triggering rule | `message` |
|---|---|---|---|
| `CYCLE_NOT_FOUND` | 404 | `{cycleId}` path variable matches no `review_cycles` row. | `Review cycle not found` |
| `REVIEW_NOT_FOUND` | 404 | `{reviewId}` path variable matches no `performance_reviews` row. Checked **before** the reviewee/reviewer scoping rule, so the response is `404` for every caller. | `Performance review not found` |
| `GOAL_NOT_FOUND` | 404 | `{goalId}` path variable matches no `performance_goals` row. | `Performance goal not found` |

Reused P0 framework codes and their Phase 1 meaning:

| `code` | HTTP | Phase 1 rule |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean Validation on the request DTOs (`validation-schema.json`), unknown `status`/`sort` query tokens, `endDate < startDate`, `page`/`size`/`deptId`/`cycleId` out of range. |
| `FORBIDDEN` | 403 | `@PreAuthorize` denied **or** the row-level scoping rule failed (caller is not the reviewee/reviewer and lacks `PERFORMANCE:VIEW`/`PERFORMANCE:ADMIN` where those are accepted). Same body for both causes. |
| `TOKEN_INVALID` | 401 | As P0. |
| `INTERNAL_ERROR` | 500 | As P0. |

## 3. Order of evaluation (frozen – Level 2 fixtures depend on it)

1. Authentication (`401 TOKEN_INVALID`)
2. `@PreAuthorize` authority (`403 FORBIDDEN`)
3. Path-variable existence (`404 CYCLE_NOT_FOUND` / `REVIEW_NOT_FOUND` / `GOAL_NOT_FOUND`)
4. Row-level scoping – reviewee / reviewer / authority (`403 FORBIDDEN`)
5. Bean Validation of the body / query (`400 VALIDATION_FAILED`)
6. Domain rules in the legacy order: `-20403` (rating) **before** `-20402` (status) on
   `manager-review`; `-20401` / `-20402` otherwise.

## 4. Declared divergences from `PKG_PERFORMANCE` (error behaviour only)

Recorded here so the scenario registry marks them `expected_diff` instead of failing the
Level 2 gate. Functional divergences are listed in `README.md`.

| Situation | Legacy | Target |
|---|---|---|
| `open` an unknown `cycleId` | `-20401` (0 rows) | `404 CYCLE_NOT_FOUND` |
| `close` a `DRAFT` / `CLOSED` / unknown cycle | silent success (unconditional `UPDATE`) | `422 -20401` / `404 CYCLE_NOT_FOUND` |
| `generate-reviews` on `IN_PROGRESS`/`CALIBRATION`/`CLOSED` | proceeds | `422 -20401` |
| self-assessment on an unknown `reviewId` | `-20402` | `404 REVIEW_NOT_FOUND` |
| manager review on a review not in `MANAGER_REVIEW`/`MEETING_SCHEDULED` | silent success (unconditional `UPDATE`) | `422 -20402` |
| acknowledge a review not in `COMPLETED` | silent 0 rows | `422 -20402` |
| add goal / update progress on a finished review | proceeds | `422 -20402` |
| caller is not reviewee/reviewer | n/a (Forms had no row-level check) | `403 FORBIDDEN` |

`ErrorCode` additions required in `hrms-common` (backend session): `CYCLE_STATUS_INVALID("-20401", UNPROCESSABLE_ENTITY, "Cannot %s cycle - %s")`,
`REVIEW_STATUS_INVALID("-20402", UNPROCESSABLE_ENTITY, "Review not found or not in correct status")`,
`RATING_OUT_OF_RANGE("-20403", BAD_REQUEST, "Rating must be between 1.0 and 5.0")`,
`CYCLE_NOT_FOUND`, `REVIEW_NOT_FOUND`, `GOAL_NOT_FOUND` (404). `-20001` already exists.
