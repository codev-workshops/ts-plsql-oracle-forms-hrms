package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.LegacyCall;
import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every registered scenario. Later phases append to this list only; the harness, runners and diff
 * report do not change. Phase 0 registers the auth/SSO smoke set (TEST_STRATEGY.md §5 row 0,
 * COMPONENT_MAPPING.md §1): login, refresh, password-change rules -20310..-20312, lockout, SSO.
 *
 * <p>Legacy side uses PKG_SECURITY directly (the Forms trigger layer has no callable surface).
 * Deliberate divergences documented in contracts/p0-foundation/README.md (SEC-01/02/05/07/08) are
 * encoded as {@code legacyExpect != targetExpect} via {@link Divergence}.
 */
public final class ScenarioRegistry {

  private ScenarioRegistry() {}

  /** Seed user from tools/fixtures/pg/04_user_accounts.sql (emp 2, STAFF). */
  static final String USER = "sarah.chen@company.com";

  static final String PASSWORD = "Welcome1!";

  /** Client address the harness reports in SSO exchanges (SsoExchangeRequest.clientIp). */
  static final String CLIENT_IP = "127.0.0.1";

  /** Legacy-side provenance when no Oracle is attached (golden-oracle mode OFF). */
  public static final String RECORDED = "recorded";

  public static final String UNTESTED_LIVE = "untested-live";

  /**
   * Scenarios whose legacy leg cannot be recorded from the PL/SQL reference alone because the
   * observable is only produced by a live Oracle Forms session (DECISION P0-D1: legacy Forms are
   * not run in this phase). The target side is still fully validated against the contract.
   */
  static final Set<String> UNTESTED_LIVE_SCENARIOS = Set.of("sso.exchange.legacy-module");

  /** Contract outcome of a legacy-module SSO exchange when the auth-service has no Oracle. */
  static final Outcome SSO_LEGACY_UNAVAILABLE = Outcome.error("SSO_LEGACY_UNAVAILABLE");

  public static List<Scenario> all() {
    List<Scenario> all = new ArrayList<>(phase0());
    all.addAll(PerformanceScenarios.all());
    all.addAll(LeaveScenarios.all());
    all.addAll(SalaryScenarios.all());
    all.addAll(EmployeeScenarios.all());
    all.addAll(PayrollScenarios.all());
    return List.copyOf(all);
  }

  private static List<Scenario> phase0() {
    return List.of(
        new Scenario(
            "auth.login.ok",
            "auth",
            plsql(
                "declare v_session number; v_emp number; begin "
                    + "pkg_security.authenticate(:username, :password, v_emp, v_session); "
                    + ":emp_id := v_emp; end;",
                List.of("emp_id")),
            post("/api/auth/login", Map.of("username", USER, "password", PASSWORD), null),
            Outcome.ok(Map.of("emp_id", "2"))),
        new Scenario(
            "auth.login.unknown-user",
            "auth",
            plsql(
                "declare v_session number; v_emp number; begin "
                    + "pkg_security.authenticate('nobody@company.com', :password, v_emp, v_session); end;",
                List.of()),
            post(
                "/api/auth/login",
                Map.of("username", "nobody@company.com", "password", PASSWORD),
                null),
            Outcome.error("-20301")),
        new Scenario(
            "auth.login.wrong-password",
            "auth",
            plsql(
                "declare v_session number; v_emp number; begin "
                    + "pkg_security.authenticate(:username, 'wrong', v_emp, v_session); end;",
                List.of()),
            post("/api/auth/login", Map.of("username", USER, "password", "wrong-password"), null),
            // SEC-01: legacy MD5 accepts/derives differently; target must reject with -20301.
            // Divergence.legacyOutcome() carries the legacy expectation for the report.
            Outcome.error("-20301")),
        new Scenario(
            "auth.refresh.rotates",
            "auth",
            null, // no legacy equivalent: Forms sessions do not rotate (SEC-07)
            new RestCall(
                "POST",
                "/api/auth/refresh",
                Map.of(),
                USER,
                true,
                List.of(
                    post("/api/auth/login", Map.of("username", USER, "password", PASSWORD), null))),
            Outcome.ok(Map.of("tokenType", "Bearer"))),
        passwordRule("auth.password.too-short", "Ab1", "-20310"),
        passwordRule("auth.password.no-upper", "abcdefgh1", "-20311"),
        passwordRule("auth.password.no-digit", "Abcdefghi", "-20312"),
        new Scenario(
            "auth.lockout.after-5-failures",
            "auth",
            plsql(
                "declare v_session number; v_emp number; begin for i in 1..5 loop begin "
                    + "pkg_security.authenticate(:username, 'wrong', v_emp, v_session); "
                    + "exception when others then null; end; end loop; "
                    + "pkg_security.authenticate(:username, 'wrong', v_emp, v_session); end;",
                List.of()),
            new RestCall(
                "POST",
                "/api/auth/login",
                Map.of("username", USER, "password", "wrong-password"),
                null,
                false,
                java.util.Collections.nCopies(
                    5,
                    post(
                        "/api/auth/login",
                        Map.of("username", USER, "password", "wrong-password"),
                        null))),
            // SEC-02: legacy never locks (-20301 forever); target returns RATE_LIMITED (429).
            Outcome.error("RATE_LIMITED")),
        new Scenario(
            "sso.exchange.legacy-module",
            "auth",
            plsql(
                "declare v_session number; begin v_session := pkg_security.create_session(:emp_id, "
                    + "'HRMS_MENU', 'PARALLEL-RUN'); :session_id := v_session; end;",
                List.of("session_id")),
            // Must target a module that is still LEGACY in the current phase (payroll until P4).
            post("/legacy/sso/exchange", Map.of("module", "payroll", "clientIp", CLIENT_IP), USER),
            Outcome.ok(Map.of("formsModule", "HRMS_PAYROLL"))),
        new Scenario(
            "sso.exchange.new-module-rejected",
            "auth",
            null,
            post("/legacy/sso/exchange", Map.of("module", "auth", "clientIp", CLIENT_IP), USER),
            Outcome.error("SSO_MODULE_NOT_LEGACY")));
  }

  /** Legacy expectations where the contract deliberately diverges (README SEC-xx). */
  /** How the legacy column of the report was obtained when Oracle is not attached. */
  public static String legacySource(Scenario s) {
    return UNTESTED_LIVE_SCENARIOS.contains(s.id()) ? UNTESTED_LIVE : RECORDED;
  }

  /**
   * Target-side expectation for the run mode. With Oracle attached every scenario expects its
   * contract outcome; without it (golden-oracle mode OFF) the untested-live SSO exchange must be
   * answered with the contracted {@code 502 SSO_LEGACY_UNAVAILABLE}, which is what the report
   * verifies instead of the Forms module handoff.
   */
  public static Outcome targetExpect(Scenario s, boolean oracleAttached) {
    if (!oracleAttached && "sso.exchange.legacy-module".equals(s.id())) {
      return SSO_LEGACY_UNAVAILABLE;
    }
    return s.expect();
  }

  public static Outcome legacyOutcome(Scenario s) {
    if (LeaveScenarios.MODULE.equals(s.module())) {
      return LeaveScenarios.legacyOutcome(s);
    }
    if (s.id().startsWith("salary.")) {
      return SalaryScenarios.legacyOutcome(s);
    }
    if (s.id().startsWith("employee.")) {
      return EmployeeScenarios.legacyOutcome(s);
    }
    if (PayrollScenarios.MODULE.equals(s.module())) {
      return PayrollScenarios.legacyOutcome(s);
    }
    return switch (s.id()) {
      case "auth.lockout.after-5-failures" -> Outcome.error("-20301"); // SEC-02: no lockout
      default -> s.expect();
    };
  }

  private static Scenario passwordRule(String id, String newPassword, String code) {
    return new Scenario(
        id,
        "auth",
        plsql(
            "begin pkg_security.change_password(:emp_id, :password, :new_password); end;",
            List.of()),
        new RestCall(
            "PUT",
            "/api/auth/password",
            Map.of("currentPassword", PASSWORD, "newPassword", newPassword),
            USER,
            false,
            List.of()),
        Outcome.error(code));
  }

  private static LegacyCall plsql(String block, List<String> outBinds) {
    return new LegacyCall(block, outBinds);
  }

  private static RestCall post(String path, Map<String, Object> body, String auth) {
    return new RestCall("POST", path, body, auth, false, List.of());
  }
}
