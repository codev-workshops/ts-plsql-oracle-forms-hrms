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

  private static Scenario scenario(String id) {
    return ScenarioRegistry.all().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
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

  @Test
  void legacyModuleExchangeTargetsEmployeeAndExpectsTheFormsModule() throws Exception {
    Scenario s = scenario("sso.exchange.legacy-module");
    assertThat(requestOf(s).getModule()).isEqualTo(ProxyModule.EMPLOYEE);
    assertThat(s.expect()).isEqualTo(Outcome.ok(Map.of("formsModule", "HRMS_EMPLOYEE")));
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
    assertThat(others).containsExactly(ScenarioRegistry.RECORDED);

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
