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

* Without `--oracle` only the target side runs and is compared with the frozen contract. The
  legacy column reads `n/a (recorded)` for scenarios whose legacy expectation was derived from the
  PL/SQL reference and `n/a (untested-live)` for the ones that need a live Oracle Forms session
  (DECISION P0-D1: `sso.exchange.legacy-module`). For those, `ScenarioRegistry.targetExpect`
  swaps the target expectation to the contracted no-Oracle answer (`502 SSO_LEGACY_UNAVAILABLE`)
  and the verdict is `UNTESTED-LIVE`, which does not fail the run.
* Exit 0 = all PASS / UNTESTED-LIVE, 1 = differences, 2 = usage error.
* Verdicts: `PASS`, `UNTESTED-LIVE` (no Oracle; target matched the no-Oracle contract outcome),
  `TARGET-DIFF` (target ≠ contract), `ORACLE-DRIFT` (legacy ≠ its expected outcome – the golden
  oracle moved), `BOTH-DIFF`.
* Documented divergences (contracts/p0-foundation/README.md SEC-01/02/05/07/08) are encoded in
  `ScenarioRegistry.legacyOutcome`; e.g. lockout expects `-20301` from legacy and `RATE_LIMITED`
  from the target and is still `PASS`.

Phase 0 registers: `auth.login.*`, `auth.refresh.rotates`, `auth.password.{too-short,no-upper,no-digit}`
(-20310/-20311/-20312), `auth.lockout.after-5-failures`, `sso.exchange.*`. Later phases only append
to `ScenarioRegistry`.
