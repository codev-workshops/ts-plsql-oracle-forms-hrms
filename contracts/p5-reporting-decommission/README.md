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
`leave-module` services (no second implementation); identity is the JWT (`empId`,
`authorities[]`: `REPORTS:VIEW` / `PAYROLL:VIEW` reads, `ADMIN:VIEW` / `ADMIN:EDIT` reference
data + audit, `LEAVE:ADMIN` batch triggers, `PAYROLL:APPROVE` GL, `PAYROLL:EDIT` benefits +
time-attendance), actor = `jwt.empId` always, `mine=true` and every `changedBy`/`requestedBy`
are derived server-side and never accepted from the client; request validation is
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
