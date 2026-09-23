# Golden baseline of the six Oracle views

`views-baseline.csv` is the Level 3 reference (TEST_STRATEGY.md §5 row 0): the expected output of
the six `VW_*` views against the frozen seed (`data/seed/*.sql`, rendered to
`tools/fixtures/{oracle,pg}/` by `tools/fixtures/generate_fixtures.py`) evaluated at
`--as-of 2024-06-30`.

## Provenance (golden-oracle mode OFF)

No Oracle instance is available to the project, so the committed CSV was **not** captured on
Oracle. It was produced – never hand-edited – by running the reconciliation pack on PostgreSQL:

```bash
cd tools/reconcile && mvn -q package
java -jar target/hrms-tool-reconcile.jar capture \
     --pg jdbc:postgresql://localhost:5432/hrms --pg-user hrms --pg-password "$PG_PWD" \
     --as-of 2024-06-30 --out ../../tests/golden/views-baseline.csv
```

against a PostgreSQL loaded with `backend/hrms-common` Flyway migrations + `tools/fixtures/pg/*.sql`
(exactly what `HrmsPostgres` does in the Level-1 suite).

What keeps that from being self-referential: the `tests/reconciliation/pg/*.sql` queries are
transcriptions of `plsql/views/*.sql` whose Oracle-specific semantics are pinned by independent
Level-1 tests in `tools/reconcile` (`PgReconciliationQueriesTest`):

* `TENURE_YEARS` = `TRUNC(MONTHS_BETWEEN(:as_of, HIRE_DATE) / 12, 1)` including the
  `(day1 - day2) / 31` fraction Oracle adds when the days of month differ (e.g. emp 20 hired
  2014-05-12 → `10.1`, not `10`). Every row of the committed CSV is re-checked against a Java
  reference implementation of `MONTHS_BETWEEN`, so the baseline cannot inherit a PostgreSQL-side
  shortcut such as `age()`.
* every view returns rows for the seed (leave, payroll and pending-approval views included, from
  `data/seed/03_transaction_data.sql`), so no view reconciles vacuously.

* `VW_ORG_HIERARCHY` walks **all** employees and filters `EMPLOYMENT_STATUS = 'ACTIVE'` only in
  the outer select, because Oracle applies the view `WHERE` after `CONNECT BY`: active reports of a
  terminated manager stay in the view (their `ORG_LEVEL` counts the terminated node and `ORG_PATH`
  names it) and `CONNECT_BY_ISLEAF` counts terminated children. The pristine seed has no such
  manager, so `views-baseline.csv` cannot detect a regression here; see the scenario below.

## Scenario golden: `views-terminated-mid-manager.csv`

`scenarios/terminated-mid-manager.sql` terminates emp 21 (JENNIFER PARK – the only report of
emp 20 and the manager of emps 22/23/24) on top of the pristine seed. The CSV was produced the same
way as the baseline (never hand-edited): load migrations + `tools/fixtures/pg/*.sql`, apply the
scenario with `psql -f tests/golden/scenarios/terminated-mid-manager.sql`, then

```bash
java -jar target/hrms-tool-reconcile.jar capture \
     --pg jdbc:postgresql://localhost:5432/hrms --pg-user hrms --pg-password "$PG_PWD" \
     --as-of 2024-06-30 --out ../../tests/golden/views-terminated-mid-manager.csv
```

`PgReconciliationQueriesTest.orgHierarchyKeepsActiveReportsOfTerminatedManagerLikeOracle` replays
the scenario in the Level-1 suite, asserts the Oracle semantics explicitly (22/23/24 present at
`ORG_LEVEL` 5 under `... > JENNIFER PARK`, 21 absent, 20 keeps `IS_LEAF = 0`) and reconciles all
six views against this file.

The Oracle leg itself (`capture --oracle ...`, `live`) is `untested-live` in this phase. When an
Oracle instance becomes available, re-capture with

```bash
java -jar target/hrms-tool-reconcile.jar capture \
     --oracle jdbc:oracle:thin:@//oracle:1521/HRMSPDB --oracle-user hrms --oracle-password "$ORACLE_PWD" \
     --as-of 2024-06-30 --out ../../tests/golden/views-baseline.csv
```

and any difference to the committed file is a finding against the PostgreSQL pack, not the seed.

## Consumption

```bash
java -jar target/hrms-tool-reconcile.jar compare \
     --pg jdbc:postgresql://localhost:5432/hrms --pg-user hrms --pg-password "$PG_PWD" \
     --as-of 2024-06-30 --baseline ../../tests/golden/views-baseline.csv --report target/reconcile-report.md
```

The query pack defaults to the `tests/reconciliation` directory found above the working directory
or above the jar, so the command works from the repo root or from `tools/reconcile`; pass
`--queries <dir>` to override.

Format: `view,row_no,column,value` (long form, one cell per line, `\N` for NULL, numbers in
canonical scale, dates ISO-8601). `--as-of` fixes `SYSDATE` so that `TENURE_YEARS`,
the current-year filter and the salary effective-window are evaluated identically on both sides.

## Phase 4 – payroll (`tests/golden/payroll/`)

Golden-oracle mode OFF: the legacy leg of the payroll gate is *recorded*, not captured.

* `202406.json` – every `(EMP_ID, ELEMENT_ID, AMOUNT)` `PKG_PAYROLL.calculate_payroll` would write
  for JUN-2024 over `tools/fixtures/pg/*.sql` (23 active employees, MONTHLY, 2024 rules incl. the
  `ELSE 0.05` state default), produced by `generate_recorded.py` – a line-by-line transcription of
  `plsql/packages/PKG_PAYROLL.pkb` in `Decimal`/`ROUND_HALF_UP`. Consumed by `PayrollShadowRunner`
  (`legacySource=recorded`), `backend/auth …/PayrollApiTest` and the `payroll.*` scenarios of
  `tools/parallel-run`. Signs follow `PAYROLL_DETAILS.AMOUNT` (earnings +, taxes/deductions −).
* `vw_payroll_latest-202406-approved.csv` – Level 3 expectation for
  `tests/reconciliation/pg/vw_payroll_latest.sql` once the Java JUN-2024 run is APPROVED (same long
  form as `views-baseline.csv`; ERROR rows excluded, `TOTAL_*` positive, `NET_PAY` signed sum).
  Derived from `202406.json` by the same script and asserted against PostgreSQL in
  `PayrollApiTest.calculatesSeedPeriodToTheCentAgainstRecordedLegacyPack`. `views-baseline.csv`
  keeps the seed's MAY-2024 run 9001 as latest and still reconciles after `V7__p4_payroll.sql`.

Regenerate both with `python3 tests/golden/payroll/generate_recorded.py 202406`; when an Oracle
instance becomes available, run the legacy package and diff its `PAYROLL_DETAILS` against
`202406.json` – any difference is a finding against the transcription.

## Phase 5 – reporting / integration (`views-baseline-p5-leave-summary.csv`, `feeds/`)

Golden-oracle mode OFF: PostgreSQL is the only leg; the Oracle capture stays `untested-live`.

* `views-baseline-p5-leave-summary.csv` – VAL-05 re-baseline of `VW_LEAVE_SUMMARY` after Phase 5:
  `AVAILABLE` subtracts `PENDING` (`tests/reconciliation/pg-p5/vw_leave_summary.sql`), matching
  `leave_balances.available`, `PKG_LEAVE` and `GET /api/reports/leave-summary.available`. Produced
  from `tools/fixtures/pg/*.sql` on Testcontainers PostgreSQL 16 by
  `tools/reconcile … PgReconciliationQueriesTest#p5LeaveSummaryRebaselineSubtractsPendingFromAvailable
  -Dhrms.golden.update=true` (`--as-of 2024-06-30`). The same test proves every non-`AVAILABLE`
  cell equals `views-baseline.csv` and every `AVAILABLE` equals legacy `AVAILABLE − PENDING`
  (3 seed rows shift). `views-baseline.csv` is left untouched: it is the legacy-faithful P0 oracle
  and the API's `legacyAvailable`.
* `feeds/gl_journal_9001.txt`, `feeds/benefits_20240630.txt` – exact bytes of the Java GL journal
  (payroll run 9001, pipe-delimited H/D/T) and benefits census (`2024-06-30`, SSN `***-**-dddd`)
  writers over the seed, produced by `backend/auth …/IntegrationApiTest -Dhrms.golden.update=true`;
  their SHA-256 is the recorded expectation of the `integration.*` scenarios in
  `tools/parallel-run`. Layout follows `plsql/packages/PKG_INTEGRATION.pkb` `UTL_FILE` output;
  when an Oracle instance is available, run `generate_gl_journal(9001)` / `export_benefits_feed` and
  diff – any difference is a finding against the Java writer.
