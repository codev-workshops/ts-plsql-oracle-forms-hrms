package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.LegacyCall;
import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 salary-module scenarios. Legacy expectations are recorded from {@code
 * plsql/packages/PKG_PAYROLL.pkb} and {@code plsql/triggers/trg_audit.sql}; the employee history
 * listener is owned by the later employee-service node.
 *
 * <p>Run these scenarios with {@code HRMS_FLAG_EMPLOYEE=NEW} on the target. Audit row contents are
 * gated at Level 1 by SalaryApiTest because the REST surface has no audit endpoint.
 *
 * <p>Legacy divergences (SALARY-03): legacy does not reject inactive employees, and closes only
 * rows with {@code effective_date < p_effective_date}; a same-day change can therefore leave two
 * active rows, while the contract closes the current row unconditionally.
 */
final class SalaryScenarios {

  private SalaryScenarios() {}

  static final String MODULE = "employee";
  private static final String EXEC = PerformanceScenarios.ADMIN;

  static List<Scenario> all() {
    return List.of(
        new Scenario(
            "salary.create.ok",
            MODULE,
            plsql(
                "declare v_id number; begin v_id := pkg_payroll.create_salary_record("
                    + "1, 460000, date '2030-01-01', 'USD', 'MONTHLY', 'ANNUAL', 'MERIT', :user);"
                    + " select base_salary, active_flag, change_pct into :baseSalary, :active, :changePct"
                    + " from salary_records where salary_id = v_id; end;",
                List.of("baseSalary", "active", "changePct")),
            call(
                "POST",
                "/api/employees/1/salary",
                Map.of(
                    "effectiveDate", "2030-01-01",
                    "baseSalary", "460000.00",
                    "changeReason", "MERIT"),
                EXEC),
            Outcome.ok(Map.of("baseSalary", "460000.00", "active", "true", "changePct", "2.22"))),
        new Scenario(
            "salary.change.closes-prior",
            MODULE,
            plsql(
                "declare v_id number; begin v_id := pkg_payroll.create_salary_record("
                    + "1, 470000, date '2031-01-01', 'USD', 'MONTHLY', 'ANNUAL', 'MERIT', :user);"
                    + " select end_date, active_flag into :endDate, :active from salary_records"
                    + " where emp_id = 1 and effective_date = date '2030-01-01'; end;",
                List.of("endDate", "active")),
            call("GET", "/api/employees/1/salary/history", null, EXEC)
                .withSetup(
                    List.of(
                        call(
                            "POST",
                            "/api/employees/1/salary",
                            Map.of(
                                "effectiveDate", "2030-01-01",
                                "baseSalary", "460000.00",
                                "changeReason", "MERIT"),
                            EXEC))),
            Outcome.ok(Map.of("[1].endDate", "2030-01-01", "[1].active", "false"))),
        new Scenario(
            "salary.create.not-positive",
            MODULE,
            plsql(
                "begin pkg_payroll.create_salary_record(1, 0, date '2030-01-01', 'USD',"
                    + " 'MONTHLY', 'ANNUAL', 'MERIT', :user); end;",
                List.of()),
            call(
                "POST",
                "/api/employees/1/salary",
                Map.of(
                    "effectiveDate", "2030-01-01",
                    "baseSalary", "0.00",
                    "changeReason", "MERIT"),
                EXEC),
            Outcome.error("-20101")),
        new Scenario(
            "salary.change.audit-rows",
            MODULE,
            plsql(
                "declare v_id number; begin v_id := pkg_payroll.create_salary_record("
                    + "1, 480000, date '2032-01-01', 'USD', 'MONTHLY', 'ANNUAL', 'MERIT', :user);"
                    + " select base_salary, active_flag into :baseSalary, :active from salary_records"
                    + " where salary_id = v_id; end;",
                List.of("baseSalary", "active")),
            call(
                "POST",
                "/api/employees/1/salary",
                Map.of(
                    "effectiveDate", "2032-01-01",
                    "baseSalary", "480000.00",
                    "changeReason", "MERIT"),
                EXEC),
            Outcome.ok(Map.of("baseSalary", "480000.00", "active", "true"))));
  }

  static Outcome legacyOutcome(Scenario scenario) {
    if ("salary.change.closes-prior".equals(scenario.id())) {
      return Outcome.ok(Map.of("[1].endDate", "2029-12-31", "[1].active", "false"));
    }
    return scenario.expect();
  }

  private static LegacyCall plsql(String block, List<String> outBinds) {
    return new LegacyCall(block, outBinds);
  }

  private static RestCall call(String method, String path, Map<String, Object> body, String auth) {
    return new RestCall(method, path, body, auth, false, List.of());
  }
}
