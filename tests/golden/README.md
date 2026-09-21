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
