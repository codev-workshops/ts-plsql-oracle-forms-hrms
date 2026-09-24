package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.LegacyCall;
import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 employee-service scenarios. Legacy expectations are recorded from {@code
 * plsql/packages/PKG_EMPLOYEE.pkb} ({@code validate_employee}, {@code create/update/transfer/
 * terminate_employee}) and {@code plsql/triggers/trg_employees.sql} (-20501…-20504), which the
 * target reproduces as service invariants (no trigger on PostgreSQL).
 *
 * <p>Run with {@code HRMS_FLAG_EMPLOYEE=NEW}. Created employees get unique e-mails per scenario so
 * the set can run in any order on one seed. {@code employee.terminate.session-revoked} terminates
 * seed employee 12 (emily.johnson, login account, no other scenario uses her) and then proves her
 * access token is rejected. Legacy {@code terminate_employee} only carries a {@code -- TODO: Revoke
 * system access via PKG_SECURITY} and leaves the Oracle session valid, so the legacy expectation of
 * that scenario is a documented divergence (COMPONENT_MAPPING.md §3.2): recorded as a still-valid
 * session, target expects {@code TOKEN_INVALID}.
 *
 * <p>Legacy divergence (EMP-01, contracts/p3-employee/error-codes.md): a physical {@code DELETE} is
 * refused by the trigger (-20504); the target has no DELETE route and answers the same code.
 */
final class EmployeeScenarios {

  private EmployeeScenarios() {}

  static final String MODULE = "employee";
  static final String SESSION_REVOKED = "employee.terminate.session-revoked";
  static final String UPDATE_EMAIL_IN_USE = "employee.update.email-in-use";
  private static final String EXEC = PerformanceScenarios.ADMIN;
  private static final String EMILY = "emily.johnson@company.com";

  /** Legacy leg of {@link #SESSION_REVOKED}: the terminated employee's session stays usable. */
  static final Outcome LEGACY_SESSION_STILL_VALID = Outcome.ok(Map.of("empId", "12"));

  /** Legacy leg of {@link #UPDATE_EMAIL_IN_USE}: TRG_EMP_BEFORE_INSERT never fired on update. */
  static final Outcome LEGACY_UPDATE_EMAIL_ACCEPTED = Outcome.ok(Map.of());

  static Outcome legacyOutcome(Scenario scenario) {
    if (SESSION_REVOKED.equals(scenario.id())) {
      return LEGACY_SESSION_STILL_VALID;
    }
    if (UPDATE_EMAIL_IN_USE.equals(scenario.id())) {
      return LEGACY_UPDATE_EMAIL_ACCEPTED;
    }
    return scenario.expect();
  }

  static List<Scenario> all() {
    return List.of(
        new Scenario(
            "employee.create.number-from-sequence",
            MODULE,
            plsql(
                "declare v_id number; begin v_id := pkg_employee.create_employee('PR', 'CREATE',"
                    + " date '2025-06-02', 30, 50, 31, 'CHI', 'FULL_TIME', 85000,"
                    + " 'pr.create@company.com', :user);"
                    + " select regexp_replace(emp_number, '\\d', '9'), employment_status, active_flag"
                    + " into :empNumber, :employmentStatus, :active from employees where emp_id = v_id;"
                    + " end;",
                List.of("empNumber", "employmentStatus", "active")),
            call("POST", "/api/employees", create("pr.create@company.com"), EXEC),
            Outcome.ok(
                Map.of(
                    "employmentStatus", "ACTIVE",
                    "active", "true",
                    "version", "0",
                    "email", "pr.create@company.com"))),
        new Scenario(
            "employee.create.names-upper-trimmed",
            MODULE,
            plsql(
                "declare v_id number; begin v_id := pkg_employee.create_employee('  Grace ',"
                    + " 'hopper', date '2025-06-02', 30, 50, 31, 'CHI', 'FULL_TIME', 85000,"
                    + " 'pr.names@company.com', :user);"
                    + " select first_name, last_name into :firstName, :lastName from employees"
                    + " where emp_id = v_id; end;",
                List.of("firstName", "lastName")),
            call(
                "POST",
                "/api/employees",
                with(
                    with(create("pr.names@company.com"), "firstName", "  Grace "),
                    "lastName",
                    "hopper"),
                EXEC),
            Outcome.ok(Map.of("firstName", "GRACE", "lastName", "HOPPER"))),
        new Scenario(
            "employee.update.ok",
            MODULE,
            plsql(
                "begin pkg_employee.update_employee(p_emp_id => :emp_id, p_first_name => 'RENAMED',"
                    + " p_user => :user); select first_name into :firstName from employees"
                    + " where emp_id = :emp_id; end;",
                List.of("firstName")),
            call("PUT", "/api/employees/{id}", update("RENAMED", "pr.update@company.com"), EXEC)
                .withSetup(
                    List.of(call("POST", "/api/employees", create("pr.update@company.com"), EXEC))),
            Outcome.ok(Map.of("firstName", "RENAMED", "version", "1"))),
        new Scenario(
            UPDATE_EMAIL_IN_USE,
            MODULE,
            plsql(
                "begin pkg_employee.update_employee(p_emp_id => :emp_id, p_email => '"
                    + EMILY
                    + "', p_user => :user); end;",
                List.of()),
            call("PUT", "/api/employees/{id}", update("PR", EMILY), EXEC)
                .withSetup(
                    List.of(call("POST", "/api/employees", create("pr.email@company.com"), EXEC))),
            Outcome.error("-20502")),
        new Scenario(
            "employee.terminate.ok",
            MODULE,
            plsql(
                "begin pkg_employee.terminate_employee(:emp_id, date '2025-06-30', 'VOLUNTARY',"
                    + " null, :user); select employment_status, active_flag, termination_date"
                    + " into :employmentStatus, :active, :terminationDate from employees"
                    + " where emp_id = :emp_id; end;",
                List.of("employmentStatus", "active", "terminationDate")),
            call("POST", "/api/employees/{id}/terminate", terminate(), EXEC)
                .withSetup(
                    List.of(call("POST", "/api/employees", create("pr.term@company.com"), EXEC))),
            Outcome.ok(
                Map.of(
                    "employmentStatus", "TERMINATED",
                    "active", "false",
                    "terminationDate", "2025-06-30"))),
        new Scenario(
            "employee.terminate.already-terminated",
            MODULE,
            plsql(
                "begin pkg_employee.terminate_employee(:emp_id, date '2025-06-30', 'VOLUNTARY',"
                    + " null, :user); pkg_employee.terminate_employee(:emp_id, date '2025-06-30',"
                    + " 'VOLUNTARY', null, :user); end;",
                List.of()),
            call("POST", "/api/employees/{id}/terminate", terminate(), EXEC)
                .withSetup(
                    List.of(
                        call("POST", "/api/employees", create("pr.term2@company.com"), EXEC),
                        call("POST", "/api/employees/{id}/terminate", terminate(), EXEC))),
            Outcome.error("-20005")),
        new Scenario(
            SESSION_REVOKED,
            MODULE,
            plsql(
                "begin pkg_employee.terminate_employee(12, date '2025-06-30', 'VOLUNTARY', null,"
                    + " :user); end;",
                List.of()),
            call("GET", "/api/auth/me", null, EMILY)
                .withSetup(
                    List.of(
                        call(
                            "POST",
                            "/api/auth/login",
                            Map.of("username", EMILY, "password", ScenarioRegistry.PASSWORD),
                            null),
                        call("POST", "/api/employees/12/terminate", terminate(), EXEC))),
            Outcome.error("TOKEN_INVALID")),
        new Scenario(
            "employee.transfer.not-active",
            MODULE,
            plsql(
                "begin pkg_employee.transfer_employee(99, 30, null, null, null, date '2025-06-01',"
                    + " null, null, :user); end;",
                List.of()),
            call(
                "POST",
                "/api/employees/99/transfer",
                Map.of("effectiveDate", "2025-06-01", "deptId", 30),
                EXEC),
            Outcome.error("-20012")),
        new Scenario(
            "employee.transfer.ok-same-dept-writes-history",
            MODULE,
            plsql(
                "begin pkg_employee.transfer_employee(:emp_id, 30, null, null, null,"
                    + " date '2025-07-01', null, null, :user); select count(*) into :count"
                    + " from employee_history where emp_id = :emp_id and change_type = 'TRANSFER';"
                    + " end;",
                List.of("count")),
            call("GET", "/api/employees/{id}/history", null, EXEC)
                .withSetup(
                    List.of(
                        call("POST", "/api/employees", create("pr.xfer@company.com"), EXEC),
                        call(
                            "POST",
                            "/api/employees/{id}/transfer",
                            Map.of("effectiveDate", TRANSFER_EFFECTIVE_DATE, "deptId", 30),
                            EXEC))),
            Outcome.ok(Map.of("[0].changeType", "TRANSFER"))),
        // ---- PKG_EMPLOYEE.validate_employee ----------------------------------------------
        new Scenario(
            "employee.validate.names-required",
            MODULE,
            createLegacy("''", "'X'", "date '2025-06-02'", "30", "50", "31", "'v1@company.com'"),
            call("POST", "/api/employees", with(create("v1@company.com"), "firstName", " "), EXEC),
            Outcome.error("-20010")),
        new Scenario(
            "employee.validate.invalid-department",
            MODULE,
            createLegacy("'V'", "'X'", "date '2025-06-02'", "999", "50", "31", "'v2@company.com'"),
            call("POST", "/api/employees", with(create("v2@company.com"), "deptId", 999), EXEC),
            Outcome.error("-20003")),
        new Scenario(
            "employee.validate.invalid-job",
            MODULE,
            createLegacy("'V'", "'X'", "date '2025-06-02'", "30", "999", "31", "'v3@company.com'"),
            call("POST", "/api/employees", with(create("v3@company.com"), "jobId", 999), EXEC),
            Outcome.error("-20011")),
        new Scenario(
            "employee.validate.invalid-manager",
            MODULE,
            createLegacy("'V'", "'X'", "date '2025-06-02'", "30", "50", "99", "'v4@company.com'"),
            call(
                "POST", "/api/employees", with(create("v4@company.com"), "managerEmpId", 99), EXEC),
            Outcome.error("-20004")),
        new Scenario(
            "employee.validate.manager-cycle",
            MODULE,
            plsql(
                "begin pkg_employee.update_employee(p_emp_id => 3, p_manager_emp_id => 31,"
                    + " p_user => :user); end;",
                List.of()),
            call("PUT", "/api/employees/3", update3(31), EXEC)
                .withSetup(List.of(call("GET", "/api/employees/3", null, EXEC))),
            Outcome.error("-20004")),
        new Scenario(
            "employee.validate.salary-not-positive",
            MODULE,
            plsql(
                "declare v_id number; begin v_id := pkg_employee.create_employee('V', 'X',"
                    + " date '2025-06-02', 30, 50, 31, 'CHI', 'FULL_TIME', 0, 'v5@company.com',"
                    + " :user); end;",
                List.of()),
            call(
                "POST",
                "/api/employees",
                with(create("v5@company.com"), "initialSalary", "0.00"),
                EXEC),
            Outcome.error("-20101")),
        // ---- TRG_EMPLOYEES rules -----------------------------------------------------------
        new Scenario(
            "employee.trigger.hire-date-too-far",
            MODULE,
            createLegacy("'V'", "'X'", "trunc(sysdate) + 91", "30", "50", "31", "'v6@company.com'"),
            call(
                "POST",
                "/api/employees",
                with(create("v6@company.com"), "hireDate", "2099-01-01"),
                EXEC),
            Outcome.error("-20501")),
        new Scenario(
            "employee.trigger.email-in-use",
            MODULE,
            createLegacy(
                "'V'", "'X'", "date '2025-06-02'", "30", "50", "31", "'SARAH.CHEN@company.com'"),
            call("POST", "/api/employees", create("SARAH.CHEN@company.com"), EXEC),
            Outcome.error("-20502")),
        new Scenario(
            "employee.trigger.no-reactivation",
            MODULE,
            plsql(
                "begin update employees set employment_status = 'ACTIVE', active_flag = 'Y'"
                    + " where emp_id = 99; end;",
                List.of()),
            call("PUT", "/api/employees/99", update("BRIAN", "brian.foster@company.com"), EXEC)
                .withSetup(List.of(call("GET", "/api/employees/99", null, EXEC))),
            Outcome.error("-20503")),
        new Scenario(
            "employee.trigger.no-physical-delete",
            MODULE,
            plsql("begin delete from employees where emp_id = 99; end;", List.of()),
            call("DELETE", "/api/employees/99", null, EXEC),
            Outcome.error("-20504")));
  }

  /** Hire date of every employee created by {@link #create(String)}. */
  static final String HIRE_DATE = "2025-06-02";

  /**
   * History is served {@code ORDER BY effective_date DESC, hist_id DESC} (openapi.yaml), so the
   * TRANSFER row is only {@code [0]} when the transfer is effective after the HIRE row.
   */
  static final String TRANSFER_EFFECTIVE_DATE = "2025-07-01";

  private static Map<String, Object> create(String email) {
    Map<String, Object> m = new HashMap<>();
    m.put("firstName", "PR");
    m.put("lastName", "CREATE");
    m.put("hireDate", HIRE_DATE);
    m.put("deptId", 30);
    m.put("jobId", 50);
    m.put("managerEmpId", 31);
    m.put("locationCode", "CHI");
    m.put("employmentType", "FULL_TIME");
    m.put("initialSalary", "85000.00");
    m.put("email", email);
    return Map.copyOf(m);
  }

  private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
    Map<String, Object> m = new HashMap<>(base);
    m.put(key, value);
    return Map.copyOf(m);
  }

  private static Map<String, Object> update(String firstName, String email) {
    return Map.of(
        "firstName",
        firstName,
        "lastName",
        "CREATE",
        "email",
        email,
        "jobId",
        50,
        "employmentType",
        "FULL_TIME");
  }

  private static Map<String, Object> update3(int managerEmpId) {
    // seed employee 3 (CIO, job 3, IT dept 30)
    return Map.of(
        "firstName", "MICHAEL",
        "lastName", "OCONNOR",
        "email", "michael.oconnor@company.com",
        "jobId", 3,
        "managerEmpId", managerEmpId,
        "employmentType", "FULL_TIME");
  }

  private static Map<String, Object> terminate() {
    return Map.of("effectiveDate", "2025-06-30", "reason", "VOLUNTARY");
  }

  private static LegacyCall createLegacy(
      String first, String last, String hire, String dept, String job, String mgr, String email) {
    return plsql(
        "declare v_id number; begin v_id := pkg_employee.create_employee("
            + String.join(", ", first, last, hire, dept, job, mgr, "'CHI'", "'FULL_TIME'", "85000")
            + ", "
            + email
            + ", :user); end;",
        List.of());
  }

  private static LegacyCall plsql(String block, List<String> outBinds) {
    return new LegacyCall(block, outBinds);
  }

  private static RestCall call(String method, String path, Map<String, Object> body, String auth) {
    return new RestCall(method, path, body, auth, false, List.of());
  }
}
