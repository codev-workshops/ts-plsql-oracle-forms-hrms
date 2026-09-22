package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.LegacyCall;
import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 payroll scenarios (TEST_STRATEGY.md §5 row 4, COMPONENT_MAPPING.md §4). Legacy
 * expectations are recorded from {@code plsql/packages/PKG_PAYROLL.pkb} evaluated over the frozen
 * seed ({@code tools/fixtures/pg}); the per-(emp, element) legacy amounts live in {@code
 * tests/golden/payroll/202406.json} (produced by {@code generate_recorded.py}) and are consumed by
 * {@code PayrollShadowRunner}, which the {@code payroll.shadow.seed-period} scenario exposes.
 *
 * <p>Run with {@code HRMS_FLAG_PAYROLL=NEW HRMS_FLAG_PAYROLL_ENGINE=SHADOW} on the target. The
 * calculate scenarios create a fresh run on the OPEN seed period 202406 and poll {@code /status}
 * until the Spring Batch job leaves {@code CALCULATING} (RestRunner setup polling).
 *
 * <p>Legacy divergences (PAYROLL-01..03): legacy commits every 50 employees so a crash leaves a
 * partial run ({@code LEGACY_PARTIAL_COMMIT}); legacy falls back to 5% state tax for unlisted
 * states ({@code UNLISTED_STATE_FALLBACK}) where the target raises {@code MISSING_TAX_RATE}; the
 * seed has no such employee so both legs agree on 202406.
 */
final class PayrollScenarios {

  private PayrollScenarios() {}

  static final String MODULE = "payroll";

  /** emp 1, EXECUTIVE (PAYROLL:APPROVE). */
  private static final String EXEC = PerformanceScenarios.ADMIN;

  /** emp 2, STAFF: self-scope payslip only. */
  private static final String STAFF = ScenarioRegistry.USER;

  /**
   * Totals of tests/golden/payroll/202406.json (23 CALCULATED employees, 97 rows: 92 + the five
   * STATE_TAX rows of the seed EMPLOYEE_TAX_INFO employees in NY / CA / IL).
   */
  static final String SEED_GROSS = "300833.32";

  static final String SEED_TAXES = "83657.79";
  static final String SEED_NET = "217175.53";
  static final String SEED_EMPLOYEES = "23";
  static final String SEED_ROWS = "97";

  private static final Map<String, Object> REGULAR = Map.of("runType", "REGULAR");

  static List<Scenario> all() {
    return List.of(
        new Scenario(
            "payroll.run.create.closed-period",
            MODULE,
            plsql(
                "declare v_run number; begin"
                    + " v_run := pkg_payroll.create_payroll_run(202405, 'REGULAR', :user); end;",
                List.of()),
            call("POST", "/api/payroll/periods/202405/runs", REGULAR, EXEC),
            Outcome.error("-20102")),
        new Scenario(
            "payroll.run.calculate.seed-period",
            MODULE,
            plsql(
                "declare v_run number; begin"
                    + " v_run := pkg_payroll.create_payroll_run(202406, 'REGULAR', :user);"
                    + " pkg_payroll.calculate_payroll(v_run, :user);"
                    + " select status, total_gross, total_deductions, total_net, employee_count,"
                    + " error_count into :status, :totalGross, :totalDeductions, :totalNet,"
                    + " :employeeCount, :errorCount from payroll_runs where run_id = v_run; end;",
                List.of(
                    "status",
                    "totalGross",
                    "totalDeductions",
                    "totalNet",
                    "employeeCount",
                    "errorCount")),
            call("GET", "/api/payroll/periods/202406/runs?status=CALCULATED", null, EXEC)
                .withSetup(calculateFreshRun(202406)),
            Outcome.ok(
                Map.of(
                    "status", "CALCULATED",
                    "totalGross", SEED_GROSS,
                    "totalDeductions", SEED_TAXES,
                    "totalNet", SEED_NET,
                    "employeeCount", SEED_EMPLOYEES,
                    "errorCount", "0"))),
        new Scenario(
            "payroll.shadow.seed-period",
            MODULE,
            null, // the recorded legacy pack IS the legacy leg (CUTOVER_PLAN.md §8.3)
            call("GET", "/api/payroll/shadow/runs/{runId}/diff", null, EXEC)
                .withSetup(calculateFreshRun(202406)),
            Outcome.ok(
                Map.of(
                    "legacySource", "recorded",
                    "summary.employees", SEED_EMPLOYEES,
                    "summary.matched", SEED_ROWS,
                    "summary.unexplained", "0",
                    "summary.legacyOnly", "0",
                    "summary.javaOnly", "0"))),
        new Scenario(
            "payroll.run.approve.not-calculated",
            MODULE,
            plsql(
                "declare v_run number; begin"
                    + " v_run := pkg_payroll.create_payroll_run(202406, 'REGULAR', :user);"
                    + " pkg_payroll.approve_payroll_run(v_run, :user); end;",
                List.of()),
            call("POST", "/api/payroll/runs/{runId}/approve", null, EXEC)
                .withSetup(
                    List.of(call("POST", "/api/payroll/periods/202406/runs", REGULAR, EXEC))),
            Outcome.error("-20103")),
        new Scenario(
            "payroll.calculate.no-active-salary",
            MODULE,
            plsql(
                "declare v_run number; begin"
                    + " v_run := pkg_payroll.create_payroll_run(200001, 'REGULAR', :user);"
                    + " pkg_payroll.calculate_payroll(v_run, :user);"
                    + " select element_id, status, amount, substr(error_message, 1, 6),"
                    + " (select count(*) from payroll_details where run_id = v_run and emp_id = 43)"
                    + " into :elementId, :status, :amount, :errorCode, :rows"
                    + " from payroll_details where run_id = v_run and emp_id = 43; end;",
                List.of("elementId", "status", "amount", "errorCode", "rows")),
            // fixtures/payroll.sql: period 200001 predates every seed salary, so calculate_payroll
            // continues past emp 43 and leaves exactly one sentinel row (element 0, ERROR, 0).
            call("GET", "/api/payroll/runs/{runId}/details?empId=43", null, EXEC)
                .withSetup(calculateFreshRun(200001)),
            Outcome.ok(
                Map.of(
                    "content[0].elementId", "0",
                    "content[0].status", "ERROR",
                    "content[0].amount", "0.00",
                    "content[0].errorCode", "-20104",
                    "totalElements", "1"))),
        new Scenario(
            "payroll.payslip.ytd",
            MODULE,
            plsql(
                "select sum(case when element_type = 'EARNING' then amount end)"
                    + " into :ytdGross from payroll_details pd join payroll_runs pr"
                    + " on pr.run_id = pd.run_id where pd.emp_id = 2 and pd.status <> 'ERROR'"
                    + " and pr.status in ('APPROVED', 'PAID')",
                List.of("ytdGross")),
            // emp 2: run 9001 (MAY, APPROVED) 20833.33 + recorded JUN gross 31666.67 once the
            // fresh run is approved; legacy VW/PKG sum only approved runs (PayslipService).
            call("GET", "/api/payroll/runs/{runId}/payslips/2", null, STAFF)
                .withSetup(approveFreshRun()),
            Outcome.ok(
                Map.of(
                    "grossPay", "31666.67",
                    "federalTax", "8188.73",
                    "stateTax", "0.00",
                    "netPay", "21055.44",
                    "ytdGross", "52500.00"))));
  }

  static Outcome legacyOutcome(Scenario scenario) {
    return scenario.expect();
  }

  /** Create + calculate (202) + poll status until the job leaves CALCULATING. */
  private static List<RestCall> calculateFreshRun(long periodId) {
    return List.of(
        call("POST", "/api/payroll/periods/" + periodId + "/runs", REGULAR, EXEC),
        call("POST", "/api/payroll/runs/{runId}/calculate", null, EXEC),
        call("GET", "/api/payroll/runs/{runId}/status", null, EXEC));
  }

  private static List<RestCall> approveFreshRun() {
    List<RestCall> steps = new java.util.ArrayList<>(calculateFreshRun(202406));
    steps.add(call("POST", "/api/payroll/runs/{runId}/approve", null, EXEC));
    return List.copyOf(steps);
  }

  private static LegacyCall plsql(String block, List<String> outBinds) {
    return new LegacyCall(block, outBinds);
  }

  private static RestCall call(String method, String path, Map<String, Object> body, String auth) {
    return new RestCall(method, path, body, auth, false, List.of());
  }
}
