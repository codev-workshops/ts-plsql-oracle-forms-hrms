# Phase 3 – Employee contract (`salary-module` + `employee-service` + React employee pages)

Frozen on `p3-employee/contract` before any implementation exists (CUTOVER_PLAN.md §7,
TEST_STRATEGY.md §5 row 3, COMPONENT_MAPPING.md §3). Files: `openapi.yaml` (API surface),
`error-codes.md` (`ApiError.code` values, HTTP status, evaluation order, Level 2 whitelist),
and the `p3-employee` module of `frontend/src/generated/validation-schema.json`, produced by
`hrms-validation`'s `ValidationSchemaExporter` from
`backend/hrms-validation/src/main/java/com/acme/hrms/validation/dto/employee/`.

Backend and frontend may assume: every route in `openapi.yaml` exists with exactly the listed
DTOs, status codes and `x-preauthorize` authorities; `/api/employees/{id}/salary/**` is
served by `salary-module` (sole owner of `salary_records`, ARCH-01 – `employee-service` calls
its `SalaryService` Java interface in the hire / terminate transaction and never touches the
table), everything else under `/api/employees/**` by `employee-service` (sole owner of
`employees`, `employee_history`, `employee_dependents`, `emergency_contacts`); the acting
identity is always the JWT `empId` / `username` claim (no request carries an actor id; `{id}`
is the subject, scoped by the per-route "self / `EMPLOYEE:EDIT` / `PAYROLL:VIEW`" rule);
`empNumber` is server-assigned from `SEQ_EMP_NUMBER` and read-only (not a property of any
request DTO); there is no `DELETE` route and no route that sets `employmentStatus` directly –
`POST …/terminate` is the only exit from `ACTIVE`, writes against a `TERMINATED` employee are
`422 -20503`, and `DELETE` is `405 -20504`; every failure is an `ApiError {code, message,
field?, traceId}` whose `code` is one of the 14 legacy strings in `error-codes.md` §1 or a
framework code; DTO validation is exactly the exported schema (`@Email` server rule with
sub-domains, VAL-02; `@HireDateWithinLimit` with the single parameter
`HR.MAX_FUTURE_HIRE_DAYS = 90`, VAL-01; `@Ssn` marked `sensitive: true` – masked input, never
echoed, responses expose `ssnLast4` only to `EMPLOYEE:EDIT` / self; phone = 10–11 digits after
stripping, VAL-03); `GET /api/reference/departments|job-titles|locations` and the manager LOV
(`GET /api/employees?status=ACTIVE&fields=id,name,jobTitle&q=&excludeSelf=true`) are the
unchanged P0 endpoints, `JobTitleRef.gradeMinSalary/gradeMaxSalary` being advisory only
(`SalaryRecord.outOfGradeBand`, never an error); all `GET` routes are mounted at proxy flag
`employee=NEW_READONLY`, the write routes only at `employee=NEW`, and the React pages gate
every write control on `isModulePromoted('employee')` (backend guard: `409
MODULE_READ_ONLY`). Explicitly out of contract: rehire (`PKG_EMPLOYEE.rehire_employee`,
`REHIRE` history – P5), promote/demote as separate actions (a job change on `PUT` records
`PROMOTION`), `GET /api/org-chart` / `VW_ORG_HIERARCHY` (P5 read model), `PHOTO_BLOB`,
`ON_LEAVE` / `SUSPENDED` transitions (accepted as filters, produced by no P3 operation), the
seeding of the new `PAYROLL:EDIT` authority and the `EmployeeTerminatedEvent` consumers (P3
backend migration / later phases), tightening of `dateOfBirth` / address visibility beyond the
legacy form (P5 SEC item), and any Oracle / utPLSQL runtime (golden-oracle mode is OFF; Level 2
runs against PostgreSQL with `legacy_source=recorded`, Level 3 against `tests/golden/`
produced from the seed + `VW_EMPLOYEE_DETAILS` definition). Legacy behaviours intentionally
**not** reproduced are exactly the rows of `error-codes.md` §4: BUG-01 (`MAX()+1` employee
numbers), BUG-03 (trigger history into non-existent columns), BUG-07 (termination leaves
sessions valid), VAL-01 (180-day trigger limit), VAL-02 / VAL-03 (`.pll` e-mail / phone
regexes), the SSN echo of `VW_EMPLOYEE_DETAILS`, and the SEC-10 update gap of `-20502`; the
`Cascading` delete of `EMP_SALARY_REL` is dead code once `-20504` holds. COMPONENT_MAPPING.md
§11 already listed all 14 codes and was not modified; the `/contacts` path (§3.1 wrote
`emergency-contacts`) and `/salary` + `/salary/history` (§3 wrote `salary-history` /
`salary-changes`) follow the P3 assignment wording.

Exporter changes in this phase (additive, `schemaVersion: 1`): `FieldMeta.sensitive` →
`sensitive: true` on a field (masked input, never echoed); `@Ssn` and `@HireDateWithinLimit`
recognised (`pattern` / `kind: custom` rule with `parameter: HR.MAX_FUTURE_HIRE_DAYS` and the
parameter's default in the top-level `parameters` map); string `@Pattern` on non-P0 fields.
Un-annotated boolean flags (`active`) are, as in P0, not exported. Regenerate with
`ValidationSchemaExporter <path> <version>` and reformat with 2-space JSON, as for P1/P2.

Stored-form normalisation (not a contract change – request DTOs and validation are unchanged):
`employee-service` persists `firstName` / `lastName` as `UPPER(TRIM(value))` on create and update,
exactly as `PKG_EMPLOYEE.create_employee` / `update_employee` do, so `FULL_NAME` / `EMP_NAME` /
`ORG_PATH` / `MANAGER_NAME` cells reconcile with the legacy views without any name normalisation
on the Level 3 side; responses return the stored (upper-cased) form. Pinned by
`EmployeeServiceTest.namesAreStoredUpperTrimmedLikePkgEmployee` and the Level 2 scenario
`employee.create.names-upper-trimmed`.
