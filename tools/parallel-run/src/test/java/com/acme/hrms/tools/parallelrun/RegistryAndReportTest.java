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

  /**
   * P4 integration round-1 finding F1: the runner never resets the database, so payroll.* (23
   * ACTIVE employees, 97 rows) ran after employee.* had hired three and terminated one and reported
   * 26/27 employees, 4 errors, matched=88. Population-sensitive modules must precede every hire /
   * terminate scenario in the full registry.
   */
  @Test
  void populationSensitiveScenariosRunBeforeAnyHireOrTermination() {
    List<Scenario> all = ScenarioRegistry.all(TargetFlags.legacyDefaults());
    int firstMutation =
        java.util.stream.IntStream.range(0, all.size())
            .filter(i -> ScenarioRegistry.changesEmployeePopulation(all.get(i)))
            .findFirst()
            .orElseThrow();
    assertThat(all.get(firstMutation).id()).startsWith("employee.");
    for (int i = firstMutation; i < all.size(); i++) {
      assertThat(ScenarioRegistry.POPULATION_SENSITIVE_MODULES)
          .as(all.get(i).id() + " runs after " + all.get(firstMutation).id())
          .doesNotContain(all.get(i).module());
    }
    assertThat(all.stream().filter(s -> PayrollScenarios.MODULE.equals(s.module())).count())
        .isEqualTo(PayrollScenarios.all().size());
  }

  /** F4: the payroll expectations are the totals of the regenerated recorded pack, not stale. */
  @Test
  void payrollExpectationsMatchTheRecordedPack() throws Exception {
    java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
    while (root != null
        && !java.nio.file.Files.isRegularFile(root.resolve("tests/golden/payroll/202406.json"))) {
      root = root.getParent();
    }
    assertThat(root).isNotNull();
    com.fasterxml.jackson.databind.JsonNode pack =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(root.resolve("tests/golden/payroll/202406.json").toFile());
    java.math.BigDecimal gross = java.math.BigDecimal.ZERO;
    java.math.BigDecimal taxes = java.math.BigDecimal.ZERO;
    int rows = 0;
    int stateRows = 0;
    java.util.Set<Long> employees = new java.util.HashSet<>();
    for (com.fasterxml.jackson.databind.JsonNode r : pack.path("rows")) {
      assertThat(r.path("status").asText()).isEqualTo("CALCULATED");
      rows++;
      employees.add(r.path("empId").asLong());
      java.math.BigDecimal amount = new java.math.BigDecimal(r.path("amount").asText());
      if (r.path("elementId").asLong() == 1) {
        gross = gross.add(amount);
      } else {
        taxes = taxes.add(amount.abs());
      }
      if (r.path("elementId").asLong() == 101) {
        stateRows++;
      }
    }
    assertThat(stateRows).as("seed EMPLOYEE_TAX_INFO rows must yield STATE_TAX rows").isEqualTo(5);
    assertThat(String.valueOf(rows)).isEqualTo(PayrollScenarios.SEED_ROWS);
    assertThat(String.valueOf(employees.size())).isEqualTo(PayrollScenarios.SEED_EMPLOYEES);
    assertThat(gross.toPlainString()).isEqualTo(PayrollScenarios.SEED_GROSS);
    assertThat(taxes.toPlainString()).isEqualTo(PayrollScenarios.SEED_TAXES);
    assertThat(gross.subtract(taxes).toPlainString()).isEqualTo(PayrollScenarios.SEED_NET);

    String readme = java.nio.file.Files.readString(root.resolve("tools/parallel-run/README.md"));
    assertThat(readme)
        .contains(
            PayrollScenarios.SEED_GROSS
                + " / "
                + PayrollScenarios.SEED_TAXES
                + " / "
                + PayrollScenarios.SEED_NET)
        .contains(PayrollScenarios.SEED_ROWS + " `MATCH`");
  }

  /**
   * F3: the README told operators to start the target with HRMS_FLAG_PAYROLL_ENGINE=SHADOW, which
   * ProxyFlags.Flag does not have (LEGACY|JAVA) and the backend refused to start.
   */
  @Test
  void readmeDocumentsOnlyValidPayrollEngineFlags() throws Exception {
    java.nio.file.Path readme = java.nio.file.Path.of("README.md");
    if (!java.nio.file.Files.isRegularFile(readme)) {
      readme = java.nio.file.Path.of("tools/parallel-run/README.md");
    }
    String text = java.nio.file.Files.readString(readme);
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("HRMS_FLAG_PAYROLL_ENGINE=([A-Z_]+)").matcher(text);
    int found = 0;
    while (m.find()) {
      found++;
      assertThat(m.group(1)).isIn("LEGACY", "JAVA");
    }
    assertThat(found).isPositive();
    assertThat(text).contains("HRMS_FLAG_PAYROLL=NEW HRMS_FLAG_PAYROLL_ENGINE=JAVA");
    TargetFlags flags = TargetFlags.legacyDefaults().with("payroll=NEW,payroll.engine=JAVA");
    assertThat(flags.flag("payroll.engine")).isEqualTo("JAVA");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> TargetFlags.legacyDefaults().with("payroll.engine=SHADOW"))
        .isInstanceOf(IllegalArgumentException.class);
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
