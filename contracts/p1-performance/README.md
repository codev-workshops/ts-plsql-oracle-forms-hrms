# P1 Performance – contract (frozen before implementation)

Backend (`performance-service`, owner of `review_cycles` / `performance_reviews` /
`performance_goals`) and frontend (React performance pages) may assume exactly what
`openapi.yaml`, `error-codes.md` and the `p1-performance` DTOs in
`frontend/src/generated/validation-schema.json` say: the 18 operations under
`/api/performance/**` with their DTOs, the `CycleStatus` / `ReviewStatus` / `GoalCategory` /
`GoalStatus` enums and the review state machine
(`NOT_STARTED|SELF_REVIEW → MANAGER_REVIEW|MEETING_SCHEDULED → COMPLETED → ACKNOWLEDGED`),
rating `1.0–5.0` (`-20403`), weight/progress `0–100`, goal status auto-derivation from
progress, the rating-label thresholds, the `PERFORMANCE:ADMIN` / `PERFORMANCE:VIEW`
authorities, caller identity from `jwt.empId` only, the reviewee/reviewer row-level scoping
rule, the `ApiError` codes and their evaluation order, and the `audit_log` / `notifications`
rows each write produces. **Explicitly out of contract**: the SQL/JPA shape of the three
tables beyond the columns named in the DTOs, `IN_PROGRESS`/`CALIBRATION` cycle transitions
(no endpoint sets them in P1), `MEETING_SCHEDULED` (accepted as a source state, never set),
review deletion, goal editing other than progress, calibration screens, manager dashboards
beyond team-reviews/rating-distribution, the legacy performance views as endpoints (they are
Level 3 reconciliation queries only), and
any e-mail/SMS delivery behind the `notifications` rows. **Legacy behaviours intentionally
NOT reproduced**: silent no-op `UPDATE`s when a row is missing or in the wrong status
(the target answers `404`/`422` – see `error-codes.md` §4); `open`/`close` on unknown ids
reported as `-20401`; `close_review_cycle` and `submit_manager_review` running without a
status guard; `add_goal` taking `p_emp_id` from the client (the reviewee is read from the
review); the Forms `PERFORMANCE_REVIEW` / `PERFORMANCE_GOAL` blocks writing the base tables
directly (`UpdateAllowed`/`InsertAllowed="Yes"`, COMPONENT_MAPPING.md §6) – every write goes
through the service and the rating check is `VALIDATION_FAILED` + `-20403` server-side,
mirrored by the exported schema; `RETURNING`-based ids exposed as Oracle sequence numbers (ids are opaque `int64`);
row-by-row `generate_reviews_for_cycle` (target is set-based, idempotent per
`(cycleId, empId)`); `DBMS_OUTPUT`/`USER`-based `createdBy` (taken from the JWT);
and any Oracle-only reconciliation – Level 2/3 for this phase run against PostgreSQL with
`legacy_source=recorded` fixtures derived from reading `PKG_PERFORMANCE`.

## validation-schema.json – format extension in this phase

The P0 envelope (`contracts/p0-foundation/README.md`) is kept at `schemaVersion: 1` with
additive changes only, all produced by `ValidationSchemaExporter` (no longer hand-written):

- `module` is now the aggregate id `"hrms"`; a new `modules: ["p0-foundation", "p1-performance"]`
  array lists the frozen phases, and every `dtos.<Name>` carries `module` naming its owner.
- New field types `decimal` (`min`/`max` as JSON numbers, `scale` = `@Digits.fraction`) and
  `date` (`format: "date"`, ISO `yyyy-MM-dd`). Integer bounds stay `integer`.
- `rules[]` on a numeric field (kinds `min`/`max`) are produced from
  `@FieldMeta(ruleId, ruleErrorCode, ruleMessage)` so the legacy code (`-20403`) travels to
  the client exactly like the password policy codes do.
- `sourceHash` is now real (SHA-256 over `parameters` + `dtos`) and the backend snapshot test
  requires it to match; `generatorVersion` is the only provenance field allowed to differ.

Regenerate with `ValidationSchemaExporter <path> <version>` and reformat with 2-space JSON
(the committed file is byte-compared by `frontend/src/generated/__tests__`, semantically by
`hrms-validation`'s `ValidationSchemaExporterTest`).
