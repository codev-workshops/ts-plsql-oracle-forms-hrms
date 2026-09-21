# utPLSQL characterization suites (golden oracle)

These suites run **on Oracle only** (TEST_STRATEGY.md §3, CUTOVER_PLAN.md §4.2 item 0.5).
They characterize the *observed* legacy behaviour of `PKG_SECURITY`, `PKG_EMPLOYEE`,
`PKG_PAYROLL`, `PKG_LEAVE` and `PKG_PERFORMANCE` against the frozen seed in
`tools/fixtures/oracle/`. They are not a target artefact: nothing here is ported to
PostgreSQL and no PL/SQL is added to the target.

Where legacy behaviour is a known defect (SEC-01/02/05/07/08, BUG-01…06) the test asserts the
*legacy* outcome and is tagged `legacy_defect`, so the parallel-run diff report can classify
the divergence as *declared* rather than as a regression.

## Running

```bash
# requires utPLSQL v3 installed in the Oracle instance and the seed loaded
utplsql run hrms/<pwd>@//oracle:1521/HRMSPDB \
  -p=ut_pkg_security,ut_pkg_employee,ut_pkg_payroll,ut_pkg_leave,ut_pkg_performance \
  -f=ut_junit_reporter -o=target/utplsql-junit.xml
```

`tools/parallel-run` (`UtplsqlRunner`) invokes exactly this command; the JUnit XML it
produces is the "legacy" side of the Level 2 diff.

Install order: `tests/utplsql/*.pks` then `*.pkb` (each suite is a package).
