# tools/parallel-run – Level 2 harness

Runs every registered `Scenario` against the golden oracle (Oracle, `PKG_*` / utPLSQL) and the
target REST API (through the proxy) and produces a diff report (TEST_STRATEGY.md §2.2, §7).

```
cd tools/parallel-run && mvn -q package
java -jar target/hrms-tool-parallel-run.jar \
     --target http://localhost:8080 --seed-password "$SEED_PWD" \
     --oracle jdbc:oracle:thin:@//oracle:1521/HRMSPDB --oracle-user hrms --oracle-password "$ORACLE_PWD" \
     --utplsql hrms:ut_pkg_security,hrms:ut_pkg_employee,hrms:ut_pkg_payroll,hrms:ut_pkg_leave,hrms:ut_pkg_performance \
     --report target/parallel-run.md
```

* Without `--oracle` only the target side runs and is compared with the frozen contract.
* Exit 0 = all PASS, 1 = differences, 2 = usage error.
* Verdicts: `PASS`, `TARGET-DIFF` (target ≠ contract), `ORACLE-DRIFT` (legacy ≠ its expected
  outcome – the golden oracle moved), `BOTH-DIFF`.
* Documented divergences (contracts/p0-foundation/README.md SEC-01/02/05/07/08) are encoded in
  `ScenarioRegistry.legacyOutcome`; e.g. lockout expects `-20301` from legacy and `RATE_LIMITED`
  from the target and is still `PASS`.

Phase 0 registers: `auth.login.*`, `auth.refresh.rotates`, `auth.password.{too-short,no-upper,no-digit}`
(-20310/-20311/-20312), `auth.lockout.after-5-failures`, `sso.exchange.*`. Later phases only append
to `ScenarioRegistry`.
