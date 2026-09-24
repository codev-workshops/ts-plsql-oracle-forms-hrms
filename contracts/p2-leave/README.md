# Phase 2 – Leave contract (`leave-service` + React leave pages)

Frozen on `p2-leave/contract` before any implementation exists (CUTOVER_PLAN.md §6,
TEST_STRATEGY.md §5 row 2, COMPONENT_MAPPING.md §5). Files: `openapi.yaml` (API surface),
`error-codes.md` (`ApiError.code` values, HTTP status, evaluation order, Level 2 whitelist),
and the `p2-leave` module of `frontend/src/generated/validation-schema.json`, produced by
`hrms-validation`'s `ValidationSchemaExporter` from
`backend/hrms-validation/src/main/java/com/acme/hrms/validation/dto/leave/`.

Backend and frontend may assume: every route in `openapi.yaml` exists with exactly the listed
DTOs, status codes and `x-preauthorize` authorities; identity for `/mine`, `POST /requests`
and the cancel/approve/reject actions comes from the JWT `empId` claim (a client-supplied
`empId` is accepted only on `GET /api/leave/requests?empId=` under `LEAVE:VIEW_ALL` and on the
deferred P5 adjustment route under `LEAVE:ADMIN`); `available = openingBalance + accrued − used +
adjustment − pending` (table semantics, VAL-05 – not `VW_LEAVE_SUMMARY`); every failure is an
`ApiError {code, message, field?, traceId}` whose `code` is the legacy `-20xxx` string from
`error-codes.md` §1 or a P0 framework code; DTO validation is exactly the exported schema (the
frontend evaluates `kind: custom` rules `leave.dateOrder` / `leave.pastLimit` client-side, the
backend enforces the same via `@AssertTrue` and the `-20210`/`-20211` domain checks);
`GET /api/reference/leave-types?active=true` is the unchanged P0 endpoint whose `LeaveTypeRef`
carries `accrual`, `minTenureDays`, `requiresApproval`, `requiresDocument` (the legacy
`ACCRUAL_FLAG` / `MIN_TENURE_DAYS` / `REQUIRES_APPROVAL` / `REQUIRES_DOCUMENT` columns).
Explicitly out of contract: the four `/api/leave/admin/**` batch routes (declared, `x-phase: P5`,
`x-deferred: true` – not mounted in P2, the request falls through to the framework 404), the seeding of the
`LEAVE:VIEW_ALL` / `LEAVE:ADMIN` authorities (a P2 backend migration, not this contract),
`VW_LEAVE_SUMMARY` reconciliation of the `pending` offset (P5), document upload for
`requiresDocument` types, the `TAKEN` status (never produced, DATA-05), and any Oracle /
utPLSQL runtime (golden-oracle mode is OFF; Level 2 runs against PostgreSQL with
`legacy_source=recorded`). Legacy behaviours intentionally **not** reproduced are exactly
BUG-04 (carryover expiry over-deduction), BUG-05 (holidays observed on Fri/Mon are counted as
business days) and BUG-06 (AM + PM half days on the same date reported as an overlap), plus the
LOG-01 technical exception (`leave_accrual_log` `CARRYOVER`/`EXPIRY` idempotency rows that the
package never wrote) – the only rows the Level 2 diff may whitelist. Everything else is
byte-for-byte parity, including the reject notification body without a date range
(`PKG_LEAVE.pkb:309`), the two `AUDIT_LOG` rows (`UPDATE` then `STATUS_CHANGE`) per status
transition and the pre-pending carryover amount (`PKG_LEAVE.pkb:567-572`); the auto-approve defect of `REQUIRES_APPROVAL='N'` types,
the half-day input tightenings and the row-level authorization checks are contract rules for
inputs the legacy scenario set never exercised (`error-codes.md` §5), while QUIRK-01..03 are
preserved byte-for-byte. COMPONENT_MAPPING.md §11 already listed all eight leave codes and was
not modified.

Exporter changes in this phase (additive, `schemaVersion: 1`): field type `boolean`
(`Boolean`/`boolean` fields) and a single `rules[]` entry of `kind: custom` on a `date` field
whose `value` is `@FieldMeta.ruleValue` (a sibling field name or a day count). Regenerate with
`ValidationSchemaExporter <path> <version>` and reformat with 2-space JSON, as for P1.
