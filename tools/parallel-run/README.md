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

## Phase 2 – leave (`LeaveScenarios`)

Apply `fixtures/leave.sql` on top of `tools/fixtures/pg/*.sql` first (idempotent; gives emp 2 a
10-day PTO balance in the current year and in 2030..2034, a Saturday holiday for BUG-05 and a
tenure-gated leave type 99). Scenarios submit as `sarah.chen` (emp 2) and approve/reject as her
manager `james.richardson` (emp 1). Balance-observing scenarios each own a calendar year so the
projected `pending/used/available` are order-independent; numbers are projected without trailing
zeros (`5.00` → `5`) to match Oracle's `NUMBER` `getString`.

* `leave.submit.*` – happy path (`PENDING`, 5 days) and -20201/-20202/-20203 (invalid type and
  tenure)/-20210/-20211/-20212; `leave.request.not-found` → `LEAVE_REQUEST_NOT_FOUND`
  (legacy `ORA-01403`).
* `leave.cancel.*` – pending vs approved cancellation with balance restoration, -20204 on re-cancel.
* `leave.approve.*` / `leave.reject.*` – pending→used move, pending release, -20204, comments
  required (`VALIDATION_FAILED`, no legacy equivalent).
* Documented divergences (contracts/p2-leave/error-codes.md), in `LeaveScenarios.legacyOutcome`:
  `…bug-06` (AM+PM half-days same day: target `PENDING`, legacy -20202), `…bug-05`
  (Saturday holiday observed on Friday: 4 vs 5 business days), `…bug-04` (expiry forfeits only the
  remaining carryover: adjustment -2 vs -5).
* `leave.batch.{accrual,carryover,carryover.expire}` – recorded legacy expectations on the seed
  year (balance 9001, `LeaveScenarios.P5_CONTRACT`). Their `/api/leave/admin/*` routes are declared in the contract but mounted
  in P5, so until then the target must answer `404` and the row is `DEFERRED` (does not fail the
  run; the legacy leg stays `recorded`).

## Phase 3 – salary (`SalaryScenarios`)

Salary scenarios use the employee module flag; set `HRMS_FLAG_EMPLOYEE=NEW` on the target before
running the harness. The REST side exercises the salary-module routes while the legacy side records
`PKG_PAYROLL.create_salary_record` and the `TRG_SALARY_AUDIT` replacement.
