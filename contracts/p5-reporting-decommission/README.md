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
the next leave/payroll request with **no** cache invalidation to implement; admin-module must not
call payroll/leave and payroll/leave must not call admin. `roles`, `role_permissions`,
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
one active row per `(date, location|company-wide)` (`-20601`); response adds derived
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
`ADMIN:EDIT` (`-20805`, checked inside the transaction); (5) assignment limit `1..5` roles per
account, `<= 40` authorities per role; (6) roles are deleted physically only when unassigned
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
in `openapi.yaml`; their `hrms-validation` classes (`dto/admin/*` for the first three,
`dto/auth/*` for the last three, all registered under exporter module
`p5-reporting-decommission`) are produced by the backend child, which then regenerates
`frontend/src/generated/validation-schema.json` **via the exporter only** and pins the new hash
in `ValidationSchemaExporterTest`. The generated file is therefore unchanged by this contract PR
(hash `359d743ae9bef835dad6a4852767fa667820539fa6785223600d2c7f7b3659e6` remains the
pre-expansion baseline).

**Tests (frozen expectations).** Backend: `OpenApiContractTest` covers every new route
(status matrix incl. `401`/`403` per authority) – its pinned counts move from 55 P5 / 122 total
operations to **81 P5 / 148 total**, and `ErrorCodeTest#statusesMatchErrorCodesMd` requires
`ErrorCode` entries for `-20607`…`-20609`, `-20801`…`-20807`, `ROLE_NOT_FOUND`, `USER_NOT_FOUND`
(both tests fail on this contract-only commit by design until the backend child lands); admin `ReferenceOwnershipTest` asserts no
class outside `backend/admin` issues SQL on `holidays|pay_elements|tax_brackets` and no class
outside `backend/auth` on `roles|role_permissions|user_roles|user_accounts`; `mvn` reactor has
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
(c) role assignment limit `5` and role size `40` are contract constants without legacy source;
(d) account creation/password reset by admins is deferred; (e) `HOLIDAYS`/`TAX_BRACKETS`
`modified_by` columns are not added (audit row instead).
