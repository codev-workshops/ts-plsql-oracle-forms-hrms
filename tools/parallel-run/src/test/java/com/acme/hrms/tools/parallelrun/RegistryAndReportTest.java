package com.acme.hrms.tools.parallelrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistryAndReportTest {

  @Test
  void phase0RegistersTheAuthAndSsoSmokeSet() {
    List<String> ids = ScenarioRegistry.all().stream().map(Scenario::id).toList();
    assertThat(ids)
        .contains(
            "auth.login.ok",
            "auth.refresh.rotates",
            "auth.password.too-short",
            "auth.password.no-upper",
            "auth.password.no-digit",
            "auth.lockout.after-5-failures",
            "sso.exchange.legacy-module")
        .doesNotHaveDuplicates();
    Map<String, String> codes =
        Map.of(
            "auth.password.too-short", "-20310",
            "auth.password.no-upper", "-20311",
            "auth.password.no-digit", "-20312",
            "auth.lockout.after-5-failures", "RATE_LIMITED");
    for (Scenario s : ScenarioRegistry.all()) {
      if (codes.containsKey(s.id())) {
        assertThat(s.expect().errorCode()).as(s.id()).isEqualTo(codes.get(s.id()));
      }
      assertThat(s.target()).as(s.id()).isNotNull();
    }
  }

  @Test
  void phase1RegistersThePerformanceSet() {
    Map<String, String> codes =
        Map.of(
            "performance.cycle.open.not-draft", "-20401",
            "performance.cycle.close.draft", "-20401",
            "performance.review.self-assessment.wrong-status", "-20402",
            "performance.review.manager-review.wrong-status", "-20402",
            "performance.review.acknowledge.wrong-status", "-20402",
            "performance.review.manager-review.rating-out-of-range", "-20403");
    Map<String, Scenario> byId =
        ScenarioRegistry.all().stream()
            .collect(java.util.stream.Collectors.toMap(Scenario::id, s -> s));
    assertThat(byId.keySet())
        .containsAll(codes.keySet())
        .contains(
            "performance.cycle.create",
            "performance.goal.progress-100-completes",
            "performance.cycle.generate-reviews.row-count",
            "performance.cycle.generate-reviews.idempotent");
    codes.forEach(
        (id, code) -> assertThat(byId.get(id).expect().errorCode()).as(id).isEqualTo(code));
    assertThat(byId.get("performance.goal.progress-100-completes").expect().fields())
        .containsEntry("status", "COMPLETED");
    assertThat(byId.get("performance.cycle.generate-reviews.row-count").expect().fields())
        .containsEntry("generated", PerformanceScenarios.ELIGIBLE_EMPLOYEES);
    for (Scenario s : ScenarioRegistry.all()) {
      if (s.module().equals("performance")) {
        assertThat(ScenarioRegistry.legacySource(s))
            .as(s.id())
            .isEqualTo(ScenarioRegistry.RECORDED);
      }
    }
  }

  @Test
  void restRunnerResolvesCapturedIdsInPaths() {
    assertThat(
            RestRunner.resolve(
                "/api/performance/reviews/{reviewId}/goals", Map.of("reviewId", "5001")))
        .isEqualTo("/api/performance/reviews/5001/goals");
    assertThat(RestRunner.resolve("/api/performance/cycles", Map.of()))
        .isEqualTo("/api/performance/cycles");
  }

  @Test
  void lockoutIsADocumentedDivergenceFromLegacy() {
    Scenario lockout =
        ScenarioRegistry.all().stream()
            .filter(s -> s.id().equals("auth.lockout.after-5-failures"))
            .findFirst()
            .orElseThrow();
    assertThat(ScenarioRegistry.legacyOutcome(lockout)).isEqualTo(Outcome.error("-20301"));
    assertThat(lockout.expect()).isEqualTo(Outcome.error("RATE_LIMITED"));
  }

  @Test
  void reportClassifiesVerdicts() {
    DiffReport r = new DiffReport();
    Outcome ok = Outcome.ok(Map.of("emp_id", "1"));
    r.add(new DiffReport.Row("a", ok, ok, ok, ok));
    r.add(new DiffReport.Row("b", ok, ok, null, ok));
    r.add(new DiffReport.Row("c", ok, ok, ok, Outcome.error("-20301")));
    r.add(
        new DiffReport.Row(
            "d",
            Outcome.error("RATE_LIMITED"),
            Outcome.error("-20301"),
            Outcome.error("-20301"),
            Outcome.error("RATE_LIMITED")));
    r.add(new DiffReport.Row("e", ok, ok, Outcome.error("-20001"), ok));
    assertThat(r.rows().stream().map(DiffReport.Row::verdict))
        .containsExactly("PASS", "PASS", "TARGET-DIFF", "PASS", "ORACLE-DRIFT");
    assertThat(r.passed()).isFalse();
    assertThat(r.toMarkdown())
        .contains("documented divergence, expects `-20301`")
        .contains("| c |");
  }
}
