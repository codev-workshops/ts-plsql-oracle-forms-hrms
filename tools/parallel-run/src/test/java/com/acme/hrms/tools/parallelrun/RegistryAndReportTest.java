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
  void phase2RegistersTheLeaveSet() {
    Map<String, String> codes =
        Map.of(
            "leave.submit.insufficient-balance", "-20201",
            "leave.submit.overlap", "-20202",
            "leave.submit.invalid-leave-type", "-20203",
            "leave.submit.tenure-not-met", "-20203",
            "leave.cancel.cancelled.invalid-status", "-20204",
            "leave.approve.not-pending", "-20204",
            "leave.reject.not-pending", "-20204",
            "leave.submit.date-order", "-20210",
            "leave.submit.too-far-in-past", "-20211",
            "leave.submit.no-business-days", "-20212");
    Map<String, Scenario> byId =
        ScenarioRegistry.all().stream()
            .collect(java.util.stream.Collectors.toMap(Scenario::id, s -> s));
    assertThat(byId.keySet())
        .containsAll(codes.keySet())
        .contains(
            "leave.submit.ok",
            "leave.cancel.pending",
            "leave.cancel.pending.balance-restored",
            "leave.cancel.approved.balance-restored",
            "leave.approve.ok",
            "leave.approve.moves-pending-to-used",
            "leave.reject.ok.releases-pending",
            "leave.reject.comments-required",
            "leave.request.not-found",
            "leave.batch.accrual.seed-year",
            "leave.batch.carryover.seed-year",
            "leave.batch.carryover.expire.bug-04");
    codes.forEach(
        (id, code) -> assertThat(byId.get(id).expect().errorCode()).as(id).isEqualTo(code));
    assertThat(byId.get("leave.request.not-found").expect().errorCode())
        .isEqualTo("LEAVE_REQUEST_NOT_FOUND");
    assertThat(byId.get("leave.cancel.pending.balance-restored").expect().fields())
        .containsEntry("pending", "0")
        .containsEntry("available", "10");
    assertThat(byId.get("leave.approve.moves-pending-to-used").expect().fields())
        .containsEntry("used", "5");

    // BUG-04/05/06: documented divergences – contract vs. recorded legacy
    Scenario bug06 = byId.get("leave.submit.half-day.am-pm-same-day.bug-06");
    assertThat(bug06.expect().errorCode()).isNull();
    assertThat(ScenarioRegistry.legacyOutcome(bug06)).isEqualTo(Outcome.error("-20202"));
    Scenario bug05 = byId.get("leave.business-days.saturday-holiday.bug-05");
    assertThat(bug05.expect().fields()).containsEntry("businessDays", "4");
    assertThat(ScenarioRegistry.legacyOutcome(bug05).fields()).containsEntry("businessDays", "5");
    Scenario bug04 = byId.get("leave.batch.carryover.expire.bug-04");
    assertThat(LeaveScenarios.P5_CONTRACT.get(bug04.id()).fields())
        .containsEntry("adjustment", "-2");
    assertThat(ScenarioRegistry.legacyOutcome(bug04).fields()).containsEntry("adjustment", "-5");

    // batch admin routes are P5: target must 404 until then, row is DEFERRED not FAIL
    for (String id : LeaveScenarios.DEFERRED_TO_P5) {
      assertThat(ScenarioRegistry.legacySource(byId.get(id))).isEqualTo(ScenarioRegistry.RECORDED);
      assertThat(byId.get(id).expect()).isEqualTo(LeaveScenarios.NOT_MOUNTED);
      assertThat(LeaveScenarios.P5_CONTRACT).containsKey(id);
    }
    for (Scenario s : ScenarioRegistry.all()) {
      if (s.module().equals(LeaveScenarios.MODULE)
          && !LeaveScenarios.DEFERRED_TO_P5.contains(s.id())) {
        assertThat(ScenarioRegistry.legacySource(s))
            .as(s.id())
            .isEqualTo(ScenarioRegistry.RECORDED);
        assertThat(ScenarioRegistry.targetExpect(s, false)).isEqualTo(s.expect());
      }
    }
  }

  @Test
  void deferredRowsDoNotFailTheRun() {
    DiffReport r = new DiffReport();
    Outcome notMounted = LeaveScenarios.NOT_MOUNTED;
    r.add(
        new DiffReport.Row(
            "leave.batch.accrual.seed-year",
            notMounted,
            Outcome.ok(Map.of("accrued", "8.75")),
            null,
            notMounted));
    assertThat(r.rows().get(0).verdict()).isEqualTo("DEFERRED");
    assertThat(r.passed()).isTrue();
    assertThat(r.toMarkdown()).contains("1 deferred").contains("endpoint mounted in P5");
  }

  @Test
  void restRunnerRendersNumbersLikeOracleGetString() {
    com.fasterxml.jackson.databind.ObjectMapper json =
        new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(RestRunner.text(json.getNodeFactory().numberNode(new java.math.BigDecimal("5.00"))))
        .isEqualTo("5");
    assertThat(RestRunner.text(json.getNodeFactory().numberNode(new java.math.BigDecimal("0.50"))))
        .isEqualTo("0.5");
    assertThat(RestRunner.text(json.getNodeFactory().numberNode(10))).isEqualTo("10");
    assertThat(RestRunner.text(json.getNodeFactory().textNode("PENDING"))).isEqualTo("PENDING");
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
