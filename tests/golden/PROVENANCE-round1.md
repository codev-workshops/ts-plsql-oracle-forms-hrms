# views-baseline.csv – provenance (P0 integration round 1, golden-oracle mode OFF)

Tested revision: `p0-foundation/integration` @ `9e52a85cfc226eae1209d451f58b7f8b75d2d517`.

The project owner decided GOLDEN-ORACLE MODE = OFF for Phase 0: no Oracle instance is available,
so the capture documented in `README.md` (`reconcile capture --oracle …`) could not run.
Per the integration brief the file was populated from **the frozen seed + the view definitions**:

1. Empty PostgreSQL 16 (`postgres:16-alpine`), Flyway `V1__hrms_schema.sql` + `V2__p0_auth_foundation.sql`
   applied by the auth-service on start-up.
2. Seed loaded: `tools/fixtures/pg/01_reference_data.sql`, `tools/fixtures/pg/02_employee_data.sql`
   (row counts identical to the INSERT counts in `tools/fixtures/oracle/*.sql` – see
   `reports/p0-integration/round1/table-counts.csv`).
3. The six queries in `tests/reconciliation/pg/*.sql` executed with `:as_of = 2024-06-30` through the
   same code path `tools/reconcile` uses for `compare` (`ReconcileMain.capture(pg, …, oracle=false)`),
   then written with `Baseline.write` – i.e. the canonical long form `view,row_no,column,value`.
4. Each PostgreSQL query was reviewed by hand against the Oracle DDL in `schema/views/hrms_views.sql`
   (filters, joins, `TRUNC(MONTHS_BETWEEN/12,1)` → `age()` translation, `CONNECT BY` → `WITH RECURSIVE`,
   `ORDER SIBLINGS BY LAST_NAME`) on the 23 active seed employees. `VW_LEAVE_SUMMARY`,
   `VW_PAYROLL_LATEST` and `VW_PENDING_APPROVALS` are legitimately empty on the seed (no leave,
   payroll or review rows are seeded).

Status: `legacy_source=recorded` (derived, not captured on Oracle). The Oracle leg of Level 3 is
**untested-live**; when an Oracle instance exists, re-run `reconcile capture --oracle …` and diff against
this file – any difference is a translation defect in `tests/reconciliation/pg/`.

Known limitation for the future Oracle capture: `tests/reconciliation/oracle/*.sql` select from the
views, whose `SYSDATE` cannot be pinned by `--as-of`; `TENURE_YEARS`, the salary window and the
current-year filter will only match if the capture is run with the same effective date.
