# P5 Reporting / Integration, reference-data ownership, Forms + Oracle decommission – contract

Backend and frontend may assume: every route in `openapi.yaml` (routes, DTOs, status codes,
`x-preauthorize` expressions, `ApiError` codes in `error-codes.md`) is frozen; the six report
routes (`/api/reports/{employee-directory,org-hierarchy,employee-compensation,leave-summary,payroll-latest,pending-approvals}`
+ `.csv` twins) are **read-only Spring `@RestController`s over JPA/JDBC projections** in
`reporting-module` – the `VW_*` views and `PKG_REPORTING` ref cursors are *not* ported as
PostgreSQL views or PL/pgSQL, `VW_ORG_HIERARCHY`'s `CONNECT BY` becomes a `WITH RECURSIVE`
CTE with cycle protection, and each report is one of a `*Page` envelope (`items[] + page/size/totalElements`) (`application/json`)
or an RFC 4180 UTF-8 stream with the frozen header order (`text/csv`); `admin-module` is the
**single owner** of `DEPARTMENTS`, `JOB_GRADES`, `JOB_TITLES`, `LOCATIONS`, `LEAVE_TYPES`,
`SYSTEM_PARAMETERS` and `AUDIT_LOG` (ARCH-01/02; the P0 `reference-module` becomes
read-only and delegates), `integration-module` owns `INTEGRATION_LOG` and `integration_files` (staged
time-attendance rows included), and the leave batch triggers (`POST /api/admin/leave/accrual`,
`…/carryover`) are the mounted form of the P2 `x-deferred` routes and call the P2
`leave-module` services (no second implementation); identity is the P0 JWT (`sub` =
`user_accounts.user_id` as a string, `empId`, `authorities[]`: `REPORTS:VIEW` / `PAYROLL:VIEW`
reads, `ADMIN:VIEW` / `ADMIN:EDIT` reference data + audit, `LEAVE:ADMIN` batch triggers,
`PAYROLL:APPROVE` GL, `PAYROLL:EDIT` benefits + time-attendance; no `username` claim), the
audit actor written to `createdBy` / `modifiedBy` / `changedBy` / `requestedBy` / `startedBy` /
`created_by` is always `jwt.sub` (`CurrentCaller.userId()`, as in P3), `jwt.empId` is only the
subject for self-scoping (`mine=true`, manager inbox, employee lookup), and `mine=true` and
every actor value are derived server-side and never accepted from the client; request validation is
`hrms-validation` `dto/{reporting,admin,integration}/*` exported as module
`p5-reporting-decommission` of `frontend/src/generated/validation-schema.json` (guarded by
`ValidationSchemaExporterTest#p5ReportingDecommissionDtosPinContractRules` and
`validation-schema.test.ts`; cross-field rules – `maxSalary >= minSalary`,
`carryoverMax <= maxBalance`, `to >= from`, `hoursRegular + hoursOvertime <= 24` – are
`@AssertTrue` server rules surfaced as `VALIDATION_FAILED` / `-20603`, not client
pre-checks); **reference-data ownership transfers to PostgreSQL in this phase**: at the P5
cutover the `tools/cdc-sync` Oracle → PostgreSQL connectors for the seven admin tables are
stopped, the reverse-extract (`reverse-extract`, PostgreSQL → Oracle, `legacySource=pg-cdc`)
runs only for the reconciliation window so the Oracle `VW_*` characterization copy stays
comparable, and after gate 5 **all CDC is shut down** and `HRMS_ADMIN.fmb` / `HRMS_REPORTS.fmb`
are retired – nothing is written to Oracle by the target ever again; Level 2 expectations
are recorded fixtures (`legacy_source=recorded`, utPLSQL skipped), Level 3 is
`tests/reconciliation/pg/vw_*.sql` for all six views against `tests/golden/` on PostgreSQL
only, and **VAL-05 is fixed here**: `available = openingBalance + accrued - used + adjustment - pending`,
the diff against Oracle `VW_LEAVE_SUMMARY.AVAILABLE` (which omits `- PENDING`) is the
**accepted final difference** – the row carries `legacyAvailable` (JSON only) so the Level 3
pack asserts `legacyAvailable == golden.available` and `available == legacyAvailable - pending`
instead of weakening the gate. **Out of contract:** the reporting/admin/integration services,
entities, React pages (`/reports/**`, `/admin/**`, `/integration/**`) and Flyway migrations
(`integration_log`, `integration_files`) – implementation
branches; `PKG_REPORTING.turnover_report`, `new_hires_report`, `eeo_compliance_report` and
`refresh_reporting_tables` (a no-op in legacy); `PKG_INTEGRATION.sync_org_structure` and
`get_integration_status` (replaced by `GET /api/integration/files`); GL posting into a ledger
and benefits-carrier delivery (files are produced to object storage + HTTP download only);
reverse-extract, CDC shutdown and Oracle decommission live validation – delivered as code +
unit tests only, `untested-live`; any Oracle, Forms or utPLSQL runtime, emulation or
install. **Legacy behaviours intentionally NOT reproduced:** `UTL_FILE` directory objects
(`HRMS_EXPORT_DIR` / `HRMS_IMPORT_DIR`) → object storage with `GET /api/integration/files/{fileId}/content`;
`WHEN OTHERS THEN PKG_COMMON.log_error` swallowing every integration failure → explicit
`ApiError` (`-20701` … `-20704`, `-20001`) and `INTEGRATION_LOG.STATUS='FAILED'`; Forms-side
raw `ORA-00001` / `ORA-02290` on the admin grids → `-20601` … `-20606` (added to
`COMPONENT_MAPPING.md` §11 by this PR, `legacy_source=none`); `import_time_attendance`'s
unimplemented persistence (BUG-08) → rows are staged in `integration_files` (`status=STAGED`), `applied=false`,
`targetTable` / `payElementMapping` `null` and marked `x-unspecified`; `VW_LEAVE_SUMMARY`'s
missing `- PENDING` (VAL-05, above); the `CONNECT BY` path string format
(`SYS_CONNECT_BY_PATH(LAST_NAME, ' > ')`) is preserved in `orgPath` (without the leading separator) but `CONNECT_BY_ISLEAF` is
computed from the recursive CTE; and physical `DELETE` of reference rows – every admin
`DELETE` is a soft deactivation (`ACTIVE_FLAG='N'`) rejected with `-20602` while dependants
are active.

## §9.2 expansion – holidays, pay elements, tax brackets, role management (frozen amendment)

Adds the four admin features CUTOVER_PLAN.md §9.2 lists but the original contract omitted.
Every already-frozen route, DTO, code and the `jwt.sub` actor rule above is unchanged.

**Ownership and Maven reactor (cycle-free).** `admin-module` additionally owns reads **and
writes** of `HOLIDAYS`, `PAY_ELEMENTS`, `TAX_BRACKETS` (`/api/admin/{holidays,pay-elements,tax-brackets}`,
`ADMIN:VIEW` / `ADMIN:EDIT`, tag `admin-payroll-reference`). They are **not** added to the P0
`/api/reference/*` facade (which stays authenticated, read-only, `Cache-Control: private,
max-age=300` + ETag for its four lists). Domain readers stay decoupled and uncached –
`BusinessCalendar` (`hrms-common`, active `holidays`), `TaxRuleRepository` and
`EmployeePayInputRepository` (`payroll`) read the tables per request, so a write is visible to
the next leave/payroll request with **no** cache invalidation to implement. The **new**
reference-maintenance services (`HolidayService`, `PayElementService`, `TaxBracketService`)
must not call payroll/leave, and payroll/leave must not call admin; the existing
`admin -> leave` edge (`admin/pom.xml`, `AdminLeaveJobController` driving the frozen
`POST /api/leave/admin/{accrual,carryover}/run` batch jobs) is unchanged and stays allowed. `roles`, `role_permissions`,
`user_roles`, `user_accounts` stay **auth-owned** (V2, `UserAccountRepository` header). Because
`backend/auth/pom.xml` already depends on `backend/admin` (and on every other module), an
`admin -> auth` dependency is forbidden: the role-management controllers/services
(`/api/admin/authorities`, `/api/admin/roles/**`, `/api/admin/users/**`, tag `admin-roles`)
are implemented **inside `backend/auth`** (`com.acme.hrms.auth.web.RoleAdminController`,
`UserAdminController`, `service.RoleAdminService`, `UserAdminService`) and only share the URL
prefix, the `ADMIN:*` authorities and the `AUDIT_LOG` conventions with admin-module. Neither
module owns SQL on the other's tables; the React admin UI (`/admin/**`) is one app that calls
both sets of routes.

**Audit.** Every write on the seven new resources produces an `AUDIT_LOG` row
(`table_name` = `HOLIDAYS` / `PAY_ELEMENTS` / `TAX_BRACKETS` / `ROLES` / `ROLE_PERMISSIONS` /
`USER_ROLES` / `USER_ACCOUNTS`, `changed_by = jwt.sub`, old/new JSON) searchable through the frozen
`GET /api/admin/audit-log`. `HOLIDAYS` and `TAX_BRACKETS` carry no `modified_by/modified_date`
columns – their `modifiedBy/modifiedDate` are always `null` on the wire; no forward migration
is introduced for that (the audit row is the record of the actor). `user_roles.granted_by`,
`roles.created_by`, `user_accounts.modified_by` = `jwt.sub` (`varchar(30)` holds the numeric id).

**Validation rules (server, `-20603` / `VALIDATION_FAILED`; cross-field = `@AssertTrue`).**
Holidays: date in `[1990-01-01, today+10y]`; `locationCode` null or active location (`-20604`);
one active row per `(date, location|company-wide)` (`-20601`), guaranteed by a forward
migration (`V12__p5_admin_expansion.sql`, next free version after V11) adding
`create unique index uk_holidays_active_date_loc on holidays (holiday_date,
coalesce(location_code,'*')) where active_flag = 'Y'` – the Java pre-check gives the friendly
message, the index wins races, and its violation maps to the same `-20601`; response adds derived
`observedDate` (weekend shift identical to `BusinessCalendar`). Pay elements: `elementType`
never `ERROR`, never `TAX` on create (`-20607`); `calculationType` × defaults (`FLAT`/`HOURS` →
`defaultAmount >= 0`, `PERCENTAGE` → `0 < defaultPercentage <= 100`, `FORMULA` → none);
`pretaxFlag` only on `DEDUCTION`; reserved ids `0`, `1`, `100`–`103` (`PayrollConstants`,
`PayElementStartupValidator`) restrict `PUT` to name/GL/priority and refuse `DELETE`
(`-20607`); `-20602` while active `EMPLOYEE_PAY_ELEMENTS` exist. Tax brackets: `taxRate` is a
fraction in `[0,1]` (`NUMERIC(5,4)`); federal step = `stateCode` null + `filingStatus != ALL`;
state row = 2-letter `stateCode` + `ALL` + `[0, +∞)`; `bracketMax` null or `> bracketMin`;
no overlap of half-open ranges within an active `(year, filingStatus, stateCode)` ladder
(`-20608`); years with an `APPROVED`/`PAID` run are read-only (`-20609`); contiguity is
reported by `GET /api/admin/tax-brackets/ladder-gaps`, not enforced (engine → `MISSING_TAX_RATE`).
Money/rate/percentage are decimal **strings** (`Money`, `taxRate`, `defaultPercentage`) – never
floats.

**Role management security (from the P0 truth table).** Authorities required: `ADMIN:VIEW`
reads, `ADMIN:EDIT` writes (the P0 seeding grants both only to `EXECUTIVE`, grade ≥ 8).
Frozen rules: (1) seeded roles `1`–`3` are read-only (`-20802`) so the P0 gate
`TEST_STRATEGY.md §5 row 0` keeps holding; (2) **least privilege** – nobody grants an authority
they do not hold themselves (`-20806`); (3) **no self-service** – `jwt.sub == userId` is refused
on roles/status (`-20804`); (4) **last admin** – no write may leave zero `ACTIVE` accounts with
`ADMIN:EDIT` (`-20805`). Because that invariant spans every account, all three mutations that
can lower the holder count (`PUT …/users/{id}/roles`, `PUT …/users/{id}/status`,
`PUT …/roles/{id}`) serialize on one transaction-scoped
`pg_advisory_xact_lock(hashtext('hrms.admin_edit_guard'))`, write, then re-count active
`ADMIN:EDIT` holders and roll back on zero – per-row `select … for update` is explicitly **not**
sufficient; (5) no arbitrary size caps: `roleIds` and `permissions` are non-empty and distinct,
bounded only by the existing roles / the finite authority vocabulary; new `roles.role_id`
values come from `nextval('seq_role')`. **`seq_role` already exists** (V2 `create sequence
seq_role start with 1 increment by 1 cache 1`; V2 seeds roles 1–3 with literal ids and never
calls it) so V12 must **not** create it and must not `restart with 1000` blindly; it advances it
relative to whatever an upgraded database already holds:
`select setval('seq_role', greatest(1000, coalesce((select max(role_id) from roles), 0) + 1,
(select last_value + 1 from seq_role)), false)` – so the next `nextval` is `>= 1000` and strictly
greater than both `max(roles.role_id)` and the sequence's prior value (`is_called = false`
makes the set value the one returned). Pinned by `hrms-common` `RoleSequenceContractTest`
(V2 is the only creator; V12, once present, uses `setval(... greatest(... 1000 ...), false)` with
`max(role_id)` and `last_value`, and contains no `create sequence seq_role` / `alter sequence
seq_role restart`) – never `max(role_id)+1` in Java; (6) roles are deleted physically only when unassigned
(`-20803`; `ROLES` has no `active_flag`); (7) **tokens** – every successful role/status write
calls `SessionRevoker` for each affected user (all sessions: refresh tokens revoked, current
access-token `jti` revoked), the response reports `sessionsRevoked`; the target's next
`/api/auth/refresh` is `401 TOKEN_INVALID` and their next login resolves the new authorities
(`AuthService` re-reads `UserAccountRepository.authorities` at login/refresh – already true).
The assignable vocabulary (`GET /api/admin/authorities`) is the V4 `chk_rp_authority` form
restricted to authorities actually referenced by `@PreAuthorize` in the backend.
`user_accounts` **creation** and password reset stay out of this contract (P0 seeding /
`x-unspecified`); `PUT …/status` `ACTIVE` doubles as admin unlock (clears `locked_until`,
`failed_attempts`).

**Validation schema.** New request DTOs (`HolidayRequest`, `PayElementRequest`,
`TaxBracketRequest`, `RoleRequest`, `UserRolesRequest`, `UserStatusRequest`) are frozen here
in `openapi.yaml` **and** as annotated `hrms-validation` classes in this contract branch
(`dto/admin/{Holiday,PayElement,TaxBracket}Request`, `dto/auth/{Role,UserRoles,UserStatus}Request`,
all registered in `ValidationSchemaExporter` under module `p5-reporting-decommission`), so both
children build against the same frozen Bean Validation rules. `frontend/src/generated/
validation-schema.json` was regenerated **via the exporter only** (sha256
`9b2cd556a1cf43ce29d8da982bedade146bcda50ac9efe7d1098e9efcfd82300`), and the snapshot pins live
in `ValidationSchemaExporterTest.p5AdminExpansionDtosPinContractRules`,
`P5AdminRequestWireTest` and `frontend/src/generated/__tests__/validation-schema.test.ts`. One
deliberate interface decision: the v1 field vocabulary (`string|integer|decimal|boolean|date|enum`)
has no array type, so the exporter now skips `Collection`-typed fields – `RoleRequest.permissions`
and `UserRolesRequest.roleIds` are validated server-side only (`@NotEmpty`, element
`@Pattern`/`@Min`, `@AssertTrue` distinctness) and the React forms treat them as multi-selects with
no schema-driven pre-check; `schemaVersion` stays 1 (additive change).
`HolidayRequest.holidayDate` is bounded to `[1990-01-01, today + 10 **calendar** years]`
(`LocalDate.now().plusYears(10)`, so leap days do not shift the bound) and exports a `custom` rule
`holiday.dateWindow` whose `value` is the year count `10`; the frontend `evaluateCustomRule` leaves
it to the server until the frontend child adds the case (`today.plusYears(value)`, floor
`1990-01-01`). **Wire types are pinned in the DTOs**: all six bodies extend `StrictRequest`
(`additionalProperties: false` → unknown properties captured and rejected with `400
VALIDATION_FAILED`/`UnknownProperty`), and every `BigDecimal` field is bound through a strict
string deserializer – `MoneyDeserializer` (`^-?[0-9]+\.[0-9]{2}$`) on `defaultAmount`,
`defaultPercentage`, `bracketMin`, `bracketMax`, `baseTax`, and `TaxRateDeserializer`
(`^(0\.[0-9]{1,4}|1\.0{1,4})$`) on `taxRate`. A JSON number, a string with the wrong scale, or a
structure never binds: the property is left `null`, recorded as malformed, and
`requireNoMalformedProperties()` raises `400 VALIDATION_FAILED` with one
`InvalidFormat` detail per property (message = the deserializer's `MESSAGE`), evaluated after
authority/module-flag/header checks exactly as P3/P4 do (error-codes.md §3). Sign and range
(`>= 0`, `(0,100]`, `[0,1]`) stay Bean Validation (`-20603`). Backend services must reuse these
DTOs as their `@RequestBody` types – no parallel DTOs in `admin`/`auth`.

**Tests (frozen expectations).** Backend: `OpenApiContractTest` covers every new route
(status matrix incl. `401`/`403` per authority) – its pinned counts move from 55 P5 / 122 total
operations to **81 P5 / 148 total**, and `ErrorCodeTest#statusesMatchErrorCodesMd` requires
`ErrorCode` entries for `-20607`…`-20609`, `-20801`…`-20807`, `ROLE_NOT_FOUND`, `USER_NOT_FOUND`
(both tests fail on this contract-only commit by design until the backend child lands); admin `ReferenceOwnershipTest` asserts **exclusive
writes**: no `insert|update|delete` on `holidays|pay_elements|tax_brackets` outside
`backend/admin` and none on `roles|role_permissions|user_roles|user_accounts` outside
`backend/auth` – read-only domain readers (`BusinessCalendar`, `TaxRuleRepository`,
`EmployeePayInputRepository`, `UserAccountRepository.authorities`) are permitted and expected; `mvn` reactor has
no `admin -> auth` edge; `TaxBracketServiceTest` overlap/lock/shape; `PayElementServiceTest`
reserved-row matrix; `RoleAdminServiceTest` / `UserAdminServiceTest` rules (1)–(7) including a
Testcontainers assertion that a revoked user's refresh is `401`. Frontend: pages
`/admin/holidays`, `/admin/pay-elements`, `/admin/tax-brackets`, `/admin/roles`,
`/admin/users` behind `ADMIN:VIEW` with edit affordances behind `ADMIN:EDIT`;
`validation-schema.test.ts` pins the six new DTOs once regenerated. Live Oracle/Forms parity of
these grids is `untested-live` (`legacy_source=none`).

**Unresolved policy decisions (defaults frozen above, flagged for the parent):**
(a) least-privilege model is "caller must hold each granted authority" rather than role-hierarchy;
(b) tax-year lock threshold is `APPROVED`/`PAID` (a `CALCULATED` run does not lock);
(c) role-assignment and permission-set sizes are deliberately uncapped (vocabulary +
distinctness bound them);
(d) account creation/password reset by admins is deferred; (e) `HOLIDAYS`/`TAX_BRACKETS`
`modified_by` columns are not added (audit row instead); (f) V12 is the only forward migration
(unique index + `seq_role` advance via `setval`); V1–V11 stay untouched.
