package com.acme.hrms.tools.parallelrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.validation.dto.ProxyModule;
import com.acme.hrms.validation.dto.SsoExchangeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The SSO scenarios must send exactly what the frozen {@link SsoExchangeRequest} accepts
 * (lower-case ProxyModule wire value, clientIp present), otherwise the target side fails with
 * VALIDATION_FAILED before the contract behaviour under test is ever reached.
 */
class SsoScenarioContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  /** P4 target configuration: payroll cut over, engine JAVA, reporting still LEGACY. */
  private static final TargetFlags P4 =
      TargetFlags.of(
          Map.of(
              "performance", "NEW",
              "leave", "NEW",
              "employee", "NEW",
              "payroll", "NEW",
              "payroll.engine", "JAVA"));

  private static final TargetFlags ALL_NEW = P4.with("reporting=NEW");

  private static Scenario scenario(String id) {
    return scenario(id, TargetFlags.legacyDefaults());
  }

  private static Scenario scenario(String id, TargetFlags flags) {
    return ScenarioRegistry.all(flags).stream()
        .filter(s -> s.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static SsoExchangeRequest requestOf(Scenario s) throws Exception {
    return JSON.readValue(JSON.writeValueAsBytes(s.target().body()), SsoExchangeRequest.class);
  }

  @Test
  void ssoScenarioBodiesSatisfyTheFrozenRequestContract() throws Exception {
    for (Scenario s : ScenarioRegistry.all()) {
      if (!s.target().path().equals("/legacy/sso/exchange")) {
        continue;
      }
      assertThat(s.target().method()).as(s.id()).isEqualTo("POST");
      SsoExchangeRequest req = requestOf(s);
      Set<ConstraintViolation<SsoExchangeRequest>> violations = VALIDATOR.validate(req);
      assertThat(violations).as(s.id() + " " + violations).isEmpty();
      assertThat(s.target().body().get("module"))
          .as(s.id() + " wire value")
          .isEqualTo(req.getModule().wire());
    }
  }

  /**
   * P3 integration round-2 finding: with HRMS_FLAG_EMPLOYEE=NEW the employee module no longer needs
   * a Forms session, so the legacy-module exchange must target a module still LEGACY in P3
   * (payroll, cut over in P4) or the auth-service answers SSO_MODULE_NOT_LEGACY.
   */
  @Test
  void legacyModuleExchangeTargetsAModuleStillLegacyInP3() throws Exception {
    Scenario s =
        scenario(
            "sso.exchange.legacy-module",
            TargetFlags.of(Map.of("performance", "NEW", "leave", "NEW", "employee", "NEW")));
    ProxyModule module = requestOf(s).getModule();
    assertThat(module)
        .isNotIn(
            ProxyModule.AUTH, ProxyModule.PERFORMANCE, ProxyModule.LEAVE, ProxyModule.EMPLOYEE);
    assertThat(module).isEqualTo(ProxyModule.PAYROLL);
    assertThat(s.expect()).isEqualTo(Outcome.ok(Map.of("formsModule", "HRMS_PAYROLL")));
  }

  /**
   * P4 integration round-1 finding F2: with HRMS_FLAG_PAYROLL=NEW the exchange still hard-coded
   * {@code module=payroll} and the auth-service (correctly) answered SSO_MODULE_NOT_LEGACY. The
   * scenario must follow the target's flags: reporting is the module still LEGACY in P4.
   */
  @Test
  void legacyModuleExchangeFollowsTheTargetFlagsInP4() throws Exception {
    Scenario s = scenario("sso.exchange.legacy-module", P4);
    ProxyModule module = requestOf(s).getModule();
    assertThat(module).isNotEqualTo(ProxyModule.PAYROLL).isEqualTo(ProxyModule.REPORTING);
    assertThat(s.expect()).isEqualTo(Outcome.ok(Map.of("formsModule", "HRMS_MENU")));
    assertThat(s.legacy()).isNotNull();
    assertThat(ScenarioRegistry.legacySource(s)).isEqualTo(ScenarioRegistry.UNTESTED_LIVE);
    assertThat(ScenarioRegistry.targetExpect(s, false))
        .isEqualTo(Outcome.error("SSO_LEGACY_UNAVAILABLE"));

    // the same flags the README tells operators to export are what the registry reads
    TargetFlags env =
        TargetFlags.fromEnv(
            Map.of(
                "HRMS_FLAG_PERFORMANCE", "NEW",
                "HRMS_FLAG_LEAVE", "NEW",
                "HRMS_FLAG_EMPLOYEE", "NEW",
                "HRMS_FLAG_PAYROLL", "NEW",
                "HRMS_FLAG_PAYROLL_ENGINE", "JAVA"));
    assertThat(env).isEqualTo(P4);
    assertThat(env.flag("payroll.engine")).isEqualTo("JAVA");
  }

  /** Once every module is NEW no Forms session exists: the contract answer is recorded. */
  @Test
  void legacyModuleExchangeBecomesRecordedRejectionWhenNothingIsLegacy() throws Exception {
    Scenario s = scenario("sso.exchange.legacy-module", ALL_NEW);
    assertThat(requestOf(s).getModule()).isEqualTo(ProxyModule.PAYROLL);
    assertThat(s.legacy()).isNull();
    assertThat(s.expect()).isEqualTo(Outcome.error("SSO_MODULE_NOT_LEGACY"));
    assertThat(ScenarioRegistry.legacySource(s)).isEqualTo(ScenarioRegistry.RECORDED);
    assertThat(ScenarioRegistry.targetExpect(s, false)).isEqualTo(s.expect());
    assertThat(ScenarioRegistry.targetExpect(s, true)).isEqualTo(s.expect());
  }

  @Test
  void newModuleExchangeIsRejectedByTheContract() throws Exception {
    Scenario s = scenario("sso.exchange.new-module-rejected");
    assertThat(requestOf(s).getModule()).isEqualTo(ProxyModule.AUTH);
    assertThat(s.expect()).isEqualTo(Outcome.error("SSO_MODULE_NOT_LEGACY"));
    assertThat(ScenarioRegistry.legacySource(s)).isEqualTo(ScenarioRegistry.RECORDED);
  }

  @Test
  void legacyModuleExchangeIsMarkedUntestedLivePerP0D1() {
    Scenario s = scenario("sso.exchange.legacy-module");
    assertThat(ScenarioRegistry.legacySource(s)).isEqualTo(ScenarioRegistry.UNTESTED_LIVE);
    List<String> others =
        ScenarioRegistry.all().stream()
            .filter(x -> !x.id().equals(s.id()))
            .map(ScenarioRegistry::legacySource)
            .distinct()
            .toList();
    // P5 contract (error-codes.md): -206xx/-207xx rows have no legacy leg -> "none"
    assertThat(others).containsExactlyInAnyOrder(ScenarioRegistry.RECORDED, ScenarioRegistry.NONE);
    assertThat(
            ScenarioRegistry.all().stream()
                .filter(x -> ScenarioRegistry.NONE.equals(ScenarioRegistry.legacySource(x)))
                .map(Scenario::id))
        .containsExactlyInAnyOrderElementsOf(ScenarioRegistry.CONTRACT_ONLY_SCENARIOS);

    DiffReport r = new DiffReport();
    r.add(
        new DiffReport.Row(
            s.id(), s.expect(), s.expect(), null, s.expect(), ScenarioRegistry.legacySource(s)));
    assertThat(r.passed()).isTrue();
    assertThat(r.toMarkdown())
        .contains("| n/a (untested-live) |")
        .contains("| UNTESTED-LIVE |")
        .contains("1 untested-live");
  }

  /**
   * Integration round-2 finding: without {@code --oracle} the auth-service answers the contracted
   * 502 SSO_LEGACY_UNAVAILABLE, which must not be recorded as TARGET-DIFF / exit 1.
   */
  @Test
  void withoutOracleTheLegacyModuleExchangeExpectsSsoLegacyUnavailableAndPasses() {
    Scenario s = scenario("sso.exchange.legacy-module");
    Outcome noOracle = ScenarioRegistry.targetExpect(s, false);
    assertThat(noOracle).isEqualTo(Outcome.error("SSO_LEGACY_UNAVAILABLE"));
    assertThat(ScenarioRegistry.targetExpect(s, true)).isEqualTo(s.expect());

    DiffReport r = new DiffReport();
    r.add(
        new DiffReport.Row(
            s.id(),
            noOracle,
            ScenarioRegistry.legacyOutcome(s),
            null,
            Outcome.error("SSO_LEGACY_UNAVAILABLE"),
            ScenarioRegistry.legacySource(s)));
    assertThat(r.rows().get(0).verdict()).isEqualTo("UNTESTED-LIVE");
    assertThat(r.passed()).isTrue();

    DiffReport wrong = new DiffReport();
    wrong.add(
        new DiffReport.Row(
            s.id(),
            noOracle,
            ScenarioRegistry.legacyOutcome(s),
            null,
            s.expect(),
            ScenarioRegistry.legacySource(s)));
    assertThat(wrong.rows().get(0).verdict()).isEqualTo("TARGET-DIFF");
    assertThat(wrong.passed()).isFalse();
  }

  @Test
  void recordedScenariosKeepTheirContractExpectationWithoutOracle() {
    for (Scenario s : ScenarioRegistry.all()) {
      if (ScenarioRegistry.legacySource(s).equals(ScenarioRegistry.RECORDED)) {
        assertThat(ScenarioRegistry.targetExpect(s, false)).as(s.id()).isEqualTo(s.expect());
      }
    }
  }
}
