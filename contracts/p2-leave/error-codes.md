# P2 Leave – `ApiError.code` contract

Source of truth for legacy numbers: `COMPONENT_MAPPING.md` §11. This file copies the
Phase 2 subset (`-20201 … -20204`, `-20210 … -20212`, `-20001`) and adds the HTTP status,
the triggering rule and the raising component. **§11 was not modified** – it already lists
every code below (row `-20201 … -20204, -20210 … -20212 | PKG_LEAVE | 400 / 409 / 422` and
row `-20001`); this file only pins *which* of the three statuses each code takes. The
framework codes in §2 are not `-20xxx` numbers and therefore live only here (and in
`ErrorCode`).

Everything in `contracts/p0-foundation/error-codes.md` §2 (`VALIDATION_FAILED`,
`TOKEN_INVALID`, `FORBIDDEN`, `INTERNAL_ERROR`, …) and §3 (`GlobalExceptionHandler`
mapping contract, `error_log` invariants) applies unchanged to `/api/leave/**`.

## 1. Legacy codes this module may return

`code` is transmitted as a **string** carrying the Oracle `SQLCODE` verbatim (`"-20202"`),
so Level 2 parallel-run diffs (TEST_STRATEGY.md §2.2, `legacy_source=recorded`) compare it
with the `ORA-20xxx` the package would have raised.

| `code` | HTTP | Raised by (target) | Legacy origin (`PKG_LEAVE.pkb`) | Triggering rule | `field` |
|---|---|---|---|---|---|
| `-20001` | 404 | `EmployeeLookup` (shared, owned by employee-service) via `LeaveRequestService`, `LeaveBalanceService` | `submit_leave_request` `NO_DATA_FOUND` on `EMPLOYEES … EMPLOYMENT_STATUS = 'ACTIVE'` → `-20001` | Employee referenced by the JWT `empId` does not exist, or `EMPLOYMENT_STATUS <> 'ACTIVE'` / `ACTIVE_FLAG <> 'Y'`. On `GET /api/leave/requests?empId=` it means the queried `empId` does not exist (status is *not* checked there). | `null` (`empId` on the HR query) |
| `-20201` | 422 | `LeaveRequestService.submit` | `submit_leave_request`: `IF v_leave_type.ACCRUAL_FLAG = 'Y' THEN … IF v_available < v_total_days THEN -20201` | Leave type has `accrual = true` and `available` of the caller's balance row for `(leaveTypeId, currentYear)` – `0` when no row (QUIRK-01/02) – is `< totalDays`. Evaluated **last** (after overlap). | `leaveTypeId` |
| `-20202` | 409 | `LeaveRequestService.submit` | `check_leave_overlap` (`STATUS IN ('PENDING','APPROVED')`, date-range intersection) → `-20202` | Another `PENDING`/`APPROVED` request of the caller satisfies `start_date <= :endDate AND end_date >= :startDate`, **unless** (BUG-06) both requests are half days on the same single date with different `halfDayPeriod`. | `startDate` |
| `-20203` | 422 | `LeaveRequestService.submit` | `submit_leave_request`: `NO_DATA_FOUND` on `LEAVE_TYPES … ACTIVE_FLAG='Y'` → `Invalid leave type`; `SYSDATE - HIRE_DATE < MIN_TENURE_DAYS` → `Minimum tenure … not met` | (a) `leaveTypeId` matches no `leave_types` row with `active_flag='Y'`; (b) `today - hireDate` in whole days `< minTenureDays`. Same code, two frozen messages. | `leaveTypeId` |
| `-20204` | 422 | `LeaveRequestService.cancel / approve / reject` | `approve_leave_request` / `reject_leave_request` `STATUS != 'PENDING'`; `cancel_leave_request` `STATUS NOT IN ('PENDING','APPROVED')` → `-20204` | `approve`/`reject` on a request whose status is not `PENDING`; `cancel` on a request whose status is not `PENDING` or `APPROVED`. Message names the operation and the current status. | `null` |
| `-20210` | 400 | `LeaveRequestService.submit`, `BusinessCalendarController` (`/business-days`, `/team-calendar`) | `submit_leave_request`: `IF p_start_date > p_end_date THEN -20210` | `startDate > endDate` (body) or `start > end` / `from > to` (query). On the body it is normally pre-empted by the Bean Validation cross-field rule, which uses the **same code and message**, so clients see `-20210` either way. | `endDate` / `end` / `to` |
| `-20211` | 400 | `LeaveRequestService.submit` | `submit_leave_request`: `IF p_start_date < TRUNC(SYSDATE) - 5 THEN -20211` | `today - startDate > 5` calendar days (server date, UTC business date of the deployment). | `startDate` |
| `-20212` | 422 | `LeaveRequestService.submit` | `submit_leave_request`: `IF v_total_days <= 0 THEN -20212` (after `calculate_business_days`) | `businessDays(startDate, endDate, callerLocation) = 0` for a full-day request (weekends + active holidays, BUG-05 observed dates); **or** a half day whose single date is not a business day (declared tightening, §4). | `startDate` |

Frozen `message` strings (Level 2 compares them verbatim; `{…}` rendered as Oracle renders
`NUMBER || VARCHAR2`: `0.5` → `.5`, `3` → `3`, `2.5` → `2.5`):

| `code` | `message` |
|---|---|
| `-20001` | `Employee not found or not active` (P0 wording; the legacy `: {empId}` suffix is **not** appended – P0 froze it without the id) |
| `-20201` | `Insufficient leave balance. Available: {available}, Requested: {totalDays}` |
| `-20202` | `Leave request overlaps with existing request` |
| `-20203` | `Invalid leave type: {leaveTypeId}` **or** `Minimum tenure of {minTenureDays} days not met for leave type: {leaveTypeName}` |
| `-20204` | `Cannot approve request in status: {status}` / `Cannot reject request in status: {status}` / `Cannot cancel request in status: {status}` |
| `-20210` | `Start date must be before or equal to end date` |
| `-20211` | `Cannot submit leave requests more than 5 days in the past` |
| `-20212` | `Leave request must include at least one business day` |

## 2. Framework codes added by this phase (no legacy equivalent)

| `code` | HTTP | Triggering rule | `message` |
|---|---|---|---|
| `LEAVE_REQUEST_NOT_FOUND` | 404 | `{id}` path variable matches no `leave_requests` row. Checked **before** the owner/approver scoping rule, so the response is `404` for every caller. | `Leave request not found` |

`FORBIDDEN` (403) additionally covers the row-level scope rules of this module: caller is
not the owner (`cancel`), not the designated approver and without `LEAVE:APPROVE`
(`approve`/`reject`), approving/rejecting one's own request, or neither owner, approver nor
`LEAVE:VIEW_ALL` (`GET /api/leave/requests/{id}`).

`VALIDATION_FAILED` (400) additionally covers: `halfDay = true` with `startDate <> endDate`
(`field = endDate`), `halfDay = true` without `halfDayPeriod` / `halfDay = false` with
`halfDayPeriod` (`field = halfDayPeriod`), `reason`/`comments` longer than 4000, missing
`comments` on `reject`, unknown `status` filter value, `year` outside `2000–2099`,
`/business-days` or `/team-calendar` ranges longer than 366 days, paging outside `Size`.

The deferred P5 routes (`/api/leave/admin/**`) are **not mounted** in Phase 2 and therefore
produce the framework's plain 404, not an `ApiError` of this module.

## 3. Order of evaluation (frozen – Level 2 fixtures depend on it)

1. Authentication (`401 TOKEN_INVALID`)
2. `@PreAuthorize` authority (`403 FORBIDDEN`)
3. Bean Validation of body / query (`400 VALIDATION_FAILED`, `400 -20210` cross-field)
4. Caller existence and status (`404 -20001`)
5. Path-variable existence (`404 LEAVE_REQUEST_NOT_FOUND`)
6. Row-level scope (`403 FORBIDDEN`)
7. Domain rules in `PKG_LEAVE` order – `submit`: `-20203` (type) → `-20203` (tenure) →
   `-20210` → `-20211` → `-20212` → `-20202` → `-20201`; `cancel`/`approve`/`reject`: `-20204`

## 4. Declared divergences from `PKG_LEAVE` (Level 2 whitelist – exactly these)

| Id | Case | Legacy result | Target result |
|---|---|---|---|
| BUG-04 | `expire_carryover` on a row where part of the carryover was already used | `adjustment -= carryoverFromPrev` (over-deduction), re-runnable | `adjustment -= GREATEST(0, carryoverFromPrev − usedFromCarryover)`, idempotent via `leave_accrual_log` (P5 route, declared here) |
| BUG-05 | Range containing a holiday whose `HOLIDAY_DATE` falls on Saturday/Sunday | Weekend date skipped anyway → observed weekday counted as a business day | Observed weekday (Sat→Fri, Sun→Mon) excluded; `/business-days` reports `observedDate` |
| BUG-06 | Submit `PM` half day when an `AM` half day exists on the same date (or vice versa) | `-20202` | `201`; `totalDays = .5` each |

No other row may be whitelisted. The cases below are **not** behavioural divergences on the
recorded scenario set: they are inputs the legacy package either never receives (Forms
rejected them or no caller existed) or fails on in an undefined way, so the scenario
registry records the *target* expectation for them (`legacy_source=recorded`, provenance
`contract`) and a Level 2 diff there is a defect in the implementation, not a whitelist entry.

## 5. Contract rules for inputs outside the legacy scenario set

| Id | Case | Legacy result | Contract result |
|---|---|---|---|
| LEGACY-DEFECT-AUTOAPPROVE | Submit for a leave type with `REQUIRES_APPROVAL='N'` (seed `JURY`) | `-20204 Cannot approve request in status: APPROVED` (row inserted as `APPROVED`, then `approve_leave_request` refuses it) | `201` with `status = APPROVED`, `approverEmpId = null`, `used += totalDays`, `Leave Request Approved` notification |
| TIGHTEN-01 | `halfDay = true` with `startDate <> endDate` | Accepted, `totalDays = .5` over a multi-day range | `400 VALIDATION_FAILED` on `endDate` |
| TIGHTEN-02 | `halfDay = true` on a weekend / holiday | Accepted, `totalDays = .5` | `422 -20212` |
| TIGHTEN-03 | `halfDay = true` without `halfDayPeriod` | Accepted, `HALF_DAY_PERIOD = NULL` | `400 VALIDATION_FAILED` on `halfDayPeriod` |
| SCOPE-01 | `approve`/`reject` by a caller who is neither the designated approver nor `LEAVE:APPROVE`, or on their own request | Succeeded (no check in the package) | `403 FORBIDDEN` |
| SCOPE-02 | `cancel` on another employee's request | `NO_DATA_FOUND` → unhandled `ORA-01403` | `403 FORBIDDEN` |
| NOTIF-01 | `cancel` | No notification | No notification (COMPONENT_MAPPING.md §5 "notification to approver" is **not** adopted, to keep the `NOTIFICATION_QUEUE` diff empty) – listed so the decision is explicit |

LEGACY-DEFECT-AUTOAPPROVE is not in TECH_DEBT_REGISTRY.md; the contract freezes the target
behaviour (`REQUIRES_APPROVAL='N'` → created as `APPROVED`) and the P2 gate must add it to
the registry as a legacy defect, not a whitelisted difference.

Everything else – including QUIRK-01 (balance check reads the current year's row while
`pending` is booked on `startDate`'s year), QUIRK-02 (no balance row → `pending`/`used`
updates are no-ops), QUIRK-03 (`approve` overwrites `approverEmpId`) and the `reason`
overwrite on `cancel` – is preserved byte-for-byte; a diff there is a defect.
