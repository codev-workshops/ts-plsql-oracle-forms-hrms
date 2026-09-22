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
  void phase3RegistersTheEmployeeSet() {
    Map<String, String> codes = new java.util.HashMap<>();
    codes.put("employee.terminate.already-terminated", "-20005");
    codes.put("employee.terminate.session-revoked", "TOKEN_INVALID");
    codes.put("employee.transfer.not-active", "-20012");
    codes.put("employee.validate.names-required", "-20010");
    codes.put("employee.validate.invalid-department", "-20003");
    codes.put("employee.validate.invalid-job", "-20011");
    codes.put("employee.validate.invalid-manager", "-20004");
    codes.put("employee.validate.manager-cycle", "-20004");
    codes.put("employee.validate.salary-not-positive", "-20101");
    codes.put("employee.trigger.hire-date-too-far", "-20501");
    codes.put("employee.trigger.email-in-use", "-20502");
    codes.put("employee.trigger.no-reactivation", "-20503");
    codes.put("employee.trigger.no-physical-delete", "-20504");
    Map<String, Scenario> byId =
        ScenarioRegistry.all().stream()
            .collect(java.util.stream.Collectors.toMap(Scenario::id, s -> s));
    assertThat(byId.keySet())
        .containsAll(codes.keySet())
        .contains(
            "employee.create.number-from-sequence",
            "employee.create.names-upper-trimmed",
            "employee.update.ok",
            "employee.terminate.ok",
            "employee.transfer.ok-same-dept-writes-history");
    codes.forEach(
        (id, code) -> assertThat(byId.get(id).expect().errorCode()).as(id).isEqualTo(code));
    assertThat(byId.get("employee.create.number-from-sequence").expect().fields())
        .containsEntry("employmentStatus", "ACTIVE");
    assertThat(byId.get("employee.update.ok").target().method()).isEqualTo("PUT");
    assertThat(byId.get("employee.update.ok").target().setup()).hasSize(1);
    for (Scenario s : ScenarioRegistry.all()) {
      if (s.id().startsWith("employee.")) {
        assertThat(ScenarioRegistry.legacySource(s))
            .as(s.id())
            .isEqualTo(ScenarioRegistry.RECORDED);
        assertThat(ScenarioRegistry.targetExpect(s, false)).as(s.id()).isEqualTo(s.expect());
        Outcome legacy =
            s.id().equals(EmployeeScenarios.SESSION_REVOKED)
                ? EmployeeScenarios.LEGACY_SESSION_STILL_VALID
                : s.expect();
        assertThat(ScenarioRegistry.legacyOutcome(s)).as(s.id()).isEqualTo(legacy);
      }
    }
  }

  @Test
  void transferHistoryScenarioTransfersAfterTheHireRow() {
    Scenario s =
        ScenarioRegistry.all().stream()
            .filter(x -> x.id().equals("employee.transfer.ok-same-dept-writes-history"))
            .findFirst()
            .orElseThrow();
    assertThat(s.expect().fields()).containsEntry("[0].changeType", "TRANSFER");
    List<Scenario.RestCall> setup = s.target().setup();
    assertThat(setup).hasSize(2);
    java.time.LocalDate hire =
        java.time.LocalDate.parse((String) setup.get(0).body().get("hireDate"));
    java.time.LocalDate transfer =
        java.time.LocalDate.parse((String) setup.get(1).body().get("effectiveDate"));
    // history is ORDER BY effective_date DESC, hist_id DESC: [0] is TRANSFER only if it is later
    assertThat(transfer).isAfter(hire);
    assertThat(s.legacy().plsql()).contains("date '" + transfer + "'");
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

  @Test
  void phase4RegistersThePayrollSet() {
    Map<String, String> codes =
        Map.of(
            "payroll.run.create.closed-period", "-20102",
            "payroll.run.approve.not-calculated", "-20103");
    Map<String, Scenario> byId =
        ScenarioRegistry.all().stream()
            .collect(java.util.stream.Collectors.toMap(Scenario::id, s -> s));
    assertThat(byId.keySet())
        .containsAll(codes.keySet())
        .contains(
            "payroll.run.calculate.seed-period",
            "payroll.shadow.seed-period",
            "payroll.calculate.no-active-salary",
            "payroll.payslip.ytd");
    codes.forEach(
        (id, code) -> assertThat(byId.get(id).expect().errorCode()).as(id).isEqualTo(code));
    assertThat(byId.get("payroll.run.calculate.seed-period").expect().fields())
        .containsEntry("totalGross", PayrollScenarios.SEED_GROSS)
        .containsEntry("employeeCount", PayrollScenarios.SEED_EMPLOYEES)
        .containsEntry("errorCount", "0");
    assertThat(byId.get("payroll.calculate.no-active-salary").expect().fields())
        .containsEntry("content[0].errorCode", "-20104")
        .containsEntry("content[0].elementId", "0");
    assertThat(byId.get("payroll.payslip.ytd").expect().fields())
        .containsEntry("ytdGross", "52500.00");
    // every calculate flow ends its setup with a /status poll so the 202 job has settled
    for (Scenario s : ScenarioRegistry.all()) {
      if (PayrollScenarios.MODULE.equals(s.module())) {
        assertThat(ScenarioRegistry.legacySource(s))
            .as(s.id())
            .isEqualTo(ScenarioRegistry.RECORDED);
        assertThat(ScenarioRegistry.legacyOutcome(s)).as(s.id()).isEqualTo(s.expect());
        boolean calculates =
            s.target().setup().stream().anyMatch(c -> c.path().endsWith("/calculate"));
        if (calculates) {
          assertThat(s.target().setup().stream().map(Scenario.RestCall::path))
              .as(s.id())
              .anyMatch(path -> path.endsWith(RestRunner.ASYNC_STATUS_SUFFIX));
        }
      }
    }
  }

  @Test
  void selectorWalksDottedAndIndexedPaths() throws Exception {
    com.fasterxml.jackson.databind.JsonNode n =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                "{\"summary\":{\"matched\":92},\"content\":[{\"errorCode\":\"-20104\"}],"
                    + "\"totalElements\":1}");
    assertThat(RestRunner.select(n, "summary.matched").asInt()).isEqualTo(92);
    assertThat(RestRunner.select(n, "content[0].errorCode").asText()).isEqualTo("-20104");
    assertThat(RestRunner.select(n, "content[3].errorCode").isMissingNode()).isTrue();
    assertThat(RestRunner.select(n, "totalElements").asInt()).isEqualTo(1);
  }
}
