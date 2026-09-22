package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 4 payroll API on the seed: period 202405 is CLOSED (run 9001 APPROVED), 202406 is OPEN (run
 * 9002 CALCULATED, emp 2 only). The Java engine result on 202406 is compared to the cent with the
 * recorded legacy pack {@code tests/golden/payroll/202406.json}.
 */
@TestPropertySource(
    properties = {"hrms.proxy.modules.payroll=NEW", "hrms.proxy.modules.[payroll.engine]=JAVA"})
class PayrollApiTest extends AuthApiTestBase {

  private String exec;
  private String manager;
  private String staff;

  @BeforeEach
  void seed() throws Exception {
    seedAccounts();
    jdbc.update("delete from payroll_shadow_reports");
    jdbc.update("delete from payroll_details_shadow");
    jdbc.update("delete from payroll_details where run_id not in (9001, 9002)");
    jdbc.update("delete from payroll_runs where run_id not in (9001, 9002)");
    jdbc.update("delete from pay_periods where period_id > 202406");
    jdbc.update("update pay_periods set status = 'OPEN' where period_id = 202406");
    jdbc.update("update pay_periods set status = 'CLOSED' where period_id = 202405");
    exec = token(EXEC_EMAIL);
    manager = token(MANAGER_EMAIL);
    staff = token(STAFF_EMAIL);
  }

  @Test
  void listsPeriodsAndRunsWithAuthorities() throws Exception {
    mvc.perform(get("/api/payroll/periods").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/payroll/periods?status=CLOSED").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].periodId").value(202405))
        .andExpect(jsonPath("$.content[0].runCount").value(1))
        .andExpect(jsonPath("$.content[0].latestRunStatus").value("APPROVED"));
    mvc.perform(
            get("/api/payroll/periods/202406/runs").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].runId").value(9002))
        .andExpect(jsonPath("$[0].totalGross").value("20833.33"));
    mvc.perform(
            get("/api/payroll/periods/999999/runs").header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"));
  }

  @Test
  void closedPeriodRejectsCreateAndClose() throws Exception {
    mvc.perform(json(post("/api/payroll/periods/202405/runs"), exec, Map.of("runType", "REGULAR")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20102"));
    mvc.perform(post("/api/payroll/periods/202405/close").header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20102"));
    mvc.perform(
            json(post("/api/payroll/periods/202406/runs"), manager, Map.of("runType", "REGULAR")))
        .andExpect(status().isForbidden());
    mvc.perform(json(post("/api/payroll/periods/202406/runs"), exec, Map.of()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void approveRequiresCalculatedAndReverseRequiresCalculatedOrApproved() throws Exception {
    long runId = createRun();
    mvc.perform(
            post("/api/payroll/runs/" + runId + "/approve")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20103"));
    mvc.perform(
            json(
                post("/api/payroll/runs/" + runId + "/reverse"),
                exec,
                Map.of("reason", "not yet calculated")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RUN_NOT_REVERSIBLE"));
    mvc.perform(get("/api/payroll/runs/424242/status").header("Authorization", "Bearer " + exec))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void calculatesSeedPeriodToTheCentAgainstRecordedLegacyPack() throws Exception {
    long runId = createRun();
    mvc.perform(
            get("/api/payroll/periods?status=PROCESSING").header("Authorization", "Bearer " + exec))
        .andExpect(jsonPath("$.content[0].periodId").value(202406));

    mvc.perform(
            post("/api/payroll/runs/" + runId + "/calculate")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CALCULATING"));
    JsonNode st = awaitCalculated(runId);
    assertThat(st.path("processed").asInt()).isEqualTo(23);
    assertThat(st.path("total").asInt()).isEqualTo(23);
    assertThat(st.path("errorCount").asInt()).isZero();
    assertThat(st.path("jobExecutionId").isNumber()).isTrue();
    assertThat(st.path("finishedAt").isTextual()).isTrue();

    // recorded legacy rows (PKG_PAYROLL transcription over the same seed) == Java rows, to the cent
    Map<String, BigDecimal> expected = recordedPack(202406);
    Map<String, BigDecimal> actual = new HashMap<>();
    JsonNode details =
        body(
            mvc.perform(
                    get("/api/payroll/runs/" + runId + "/details?size=500")
                        .header("Authorization", "Bearer " + exec))
                .andExpect(status().isOk())
                .andReturn());
    for (JsonNode d : details.path("content")) {
      assertThat(d.path("status").asText()).isEqualTo("CALCULATED");
      actual.put(
          d.path("empId").asLong() + ":" + d.path("elementId").asLong(),
          new BigDecimal(d.path("amount").asText()));
    }
    assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    assertThat(details.path("totalElements").asInt()).isEqualTo(expected.size());

    BigDecimal gross = sum(expected, "EARNING");
    BigDecimal taxes = sum(expected, "TAX");
    mvc.perform(
            get("/api/payroll/periods/202406/runs?status=CALCULATED")
                .header("Authorization", "Bearer " + exec))
        .andExpect(
            jsonPath("$[?(@.runId == " + runId + ")].totalGross").value(gross.toPlainString()))
        .andExpect(
            jsonPath("$[?(@.runId == " + runId + ")].totalDeductions").value(taxes.toPlainString()))
        .andExpect(
            jsonPath("$[?(@.runId == " + runId + ")].totalNet")
                .value(gross.subtract(taxes).toPlainString()))
        .andExpect(jsonPath("$[?(@.runId == " + runId + ")].employeeCount").value(23))
        .andExpect(jsonPath("$[?(@.runId == " + runId + ")].engine").value("JAVA"));

    // payslip: self-scope for staff, signed lines, positive aggregates, reporting YTD from APPROVED
    // runs only
    BigDecimal emp2Gross = expected.get("2:1");
    BigDecimal emp2Fed = expected.get("2:100").negate();
    mvc.perform(
            get("/api/payroll/runs/" + runId + "/payslips/2")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.empNumber").value("EMP-000002"))
        .andExpect(jsonPath("$.periodName").value("JUN-2024"))
        .andExpect(jsonPath("$.runStatus").value("CALCULATED"))
        .andExpect(jsonPath("$.grossPay").value(emp2Gross.toPlainString()))
        .andExpect(jsonPath("$.federalTax").value(emp2Fed.toPlainString()))
        .andExpect(jsonPath("$.stateTax").value("0.00"))
        .andExpect(jsonPath("$.otherDeductions").value("0.00"))
        .andExpect(jsonPath("$.ytdGross").value("20833.33"))
        .andExpect(
            jsonPath("$.lines[?(@.elementId == 100)].amount")
                .value(expected.get("2:100").toPlainString()));
    mvc.perform(
            get("/api/payroll/runs/" + runId + "/payslips/1")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/payroll/runs/" + runId + "/payslips/424242")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("-20001"));
    mvc.perform(
            get("/api/payroll/runs/" + runId + "/payslips/99")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYSLIP_NOT_FOUND"));

    // register: RFC 4180 CRLF, sorted by last name, bank columns masked and gated by
    // PAYROLL:APPROVE
    MvcResult csv =
        mvc.perform(
                get("/api/payroll/runs/" + runId + "/register.csv")
                    .header("Authorization", "Bearer " + manager))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.startsWith(
                            "attachment; filename=\"PAY_REGISTER_" + runId + "_")))
            .andReturn();
    String text = csv.getResponse().getContentAsString();
    String[] lines = text.split("\r\n");
    assertThat(lines).hasSize(24);
    assertThat(lines[0])
        .isEqualTo(
            "EMP_NUMBER,EMPLOYEE_NAME,DEPARTMENT,GROSS_PAY,FED_TAX,STATE_TAX,SS_TAX,MEDICARE,DEDUCTIONS,NET_PAY");
    List<String> lastNames =
        jdbc.queryForList(
            "select upper(last_name) from employees where employment_status = 'ACTIVE'"
                + " and active_flag = 'Y' order by last_name, first_name, emp_number",
            String.class);
    assertThat(lines[1].toUpperCase()).contains(lastNames.get(0));
    assertThat(lines[23].toUpperCase()).contains(lastNames.get(22));
    assertThat(text).doesNotContain("\n\n");
    mvc.perform(
            get("/api/payroll/runs/" + runId + "/register.csv?includeBank=true")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isForbidden());
    String banked =
        mvc.perform(
                get("/api/payroll/runs/" + runId + "/register.csv?includeBank=true")
                    .header("Authorization", "Bearer " + exec))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(banked.split("\r\n")[0]).endsWith(",BANK_NAME,ROUTING_LAST4,ACCOUNT_LAST4");
    assertThat(banked).doesNotContainPattern("\\d{9}");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where action_type = 'REGISTER_DOWNLOAD'",
                Integer.class))
        .isEqualTo(2);

    // shadow gate: recorded legacy == Java, zero unexplained
    mvc.perform(
            get("/api/payroll/shadow/runs/" + runId + "/diff")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/payroll/shadow/runs/" + runId + "/diff")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.legacySource").value("recorded"))
        .andExpect(jsonPath("$.engineFlag").value("JAVA"))
        .andExpect(jsonPath("$.taxYear").value(2024))
        .andExpect(jsonPath("$.summary.employees").value(23))
        .andExpect(jsonPath("$.summary.matched").value(expected.size()))
        .andExpect(jsonPath("$.summary.unexplained").value(0))
        .andExpect(jsonPath("$.summary.legacyOnly").value(0))
        .andExpect(jsonPath("$.summary.javaOnly").value(0))
        .andExpect(jsonPath("$.summary.netDeltaCents").value(0));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payroll_shadow_reports where run_id = ?",
                Integer.class,
                runId))
        .isEqualTo(1);
    mvc.perform(get("/api/payroll/shadow/runs/9002/diff").header("Authorization", "Bearer " + exec))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SHADOW_REPORT_NOT_FOUND"));

    // approve → reporting YTD now includes this run; second approve / calculate rejected
    mvc.perform(
            post("/api/payroll/runs/" + runId + "/approve")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.run.status").value("APPROVED"))
        .andExpect(jsonPath("$.run.approvedBy").isNotEmpty())
        .andExpect(jsonPath("$.warnings.length()").value(0));
    assertPayrollLatestMatchesGolden(202406);
    mvc.perform(
            get("/api/payroll/runs/" + runId + "/payslips/2")
                .header("Authorization", "Bearer " + staff))
        .andExpect(
            jsonPath("$.ytdGross")
                .value(new BigDecimal("20833.33").add(emp2Gross).toPlainString()));
    mvc.perform(
            post("/api/payroll/runs/" + runId + "/approve")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20103"));
    mvc.perform(
            post("/api/payroll/runs/" + runId + "/calculate")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RUN_NOT_CALCULABLE"));

    // reverse → details REVERSED, period back to OPEN, second reverse rejected
    mvc.perform(
            json(
                post("/api/payroll/runs/" + runId + "/reverse"),
                exec,
                Map.of("reason", "test reversal")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVERSED"));
    assertThat(
            jdbc.queryForObject(
                "select count(distinct status) from payroll_details where run_id = ?",
                Integer.class,
                runId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select min(status) from payroll_details where run_id = ?", String.class, runId))
        .isEqualTo("REVERSED");
    assertThat(
            jdbc.queryForObject(
                "select status from pay_periods where period_id = 202406", String.class))
        .isEqualTo("OPEN");
    mvc.perform(
            json(post("/api/payroll/runs/" + runId + "/reverse"), exec, Map.of("reason", "again")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RUN_NOT_REVERSIBLE"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where action_type in ('PAYROLL_APPROVE','PAYROLL_REVERSE')",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void employeeWithoutSalaryGetsOneErrorRowAndRunStillCalculates() throws Exception {
    jdbc.update("update salary_records set end_date = DATE '2024-01-31' where emp_id = 43");
    try {
      long runId = createRun();
      mvc.perform(
              post("/api/payroll/runs/" + runId + "/calculate")
                  .header("Authorization", "Bearer " + exec))
          .andExpect(status().isAccepted());
      JsonNode st = awaitCalculated(runId);
      assertThat(st.path("errorCount").asInt()).isEqualTo(1);
      assertThat(st.path("processed").asInt()).isEqualTo(23);
      mvc.perform(
              get("/api/payroll/runs/" + runId + "/details?empId=43")
                  .header("Authorization", "Bearer " + exec))
          .andExpect(jsonPath("$.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].elementId").value(0))
          .andExpect(jsonPath("$.content[0].elementCode").value("ERROR"))
          .andExpect(jsonPath("$.content[0].elementType").value("ERROR"))
          .andExpect(jsonPath("$.content[0].status").value("ERROR"))
          .andExpect(jsonPath("$.content[0].amount").value("0.00"))
          .andExpect(jsonPath("$.content[0].errorCode").value("-20104"));
      mvc.perform(
              get("/api/payroll/runs/" + runId + "/payslips/43")
                  .header("Authorization", "Bearer " + exec))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("-20104"));
      String csv =
          mvc.perform(
                  get("/api/payroll/runs/" + runId + "/register.csv")
                      .header("Authorization", "Bearer " + exec))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(csv).doesNotContain("EMP-000043");
      assertThat(csv.split("\r\n")).hasSize(23);
      mvc.perform(
              post("/api/payroll/runs/" + runId + "/approve")
                  .header("Authorization", "Bearer " + exec))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.warnings.length()").value(1))
          .andExpect(jsonPath("$.warnings[0].empId").value(43))
          .andExpect(jsonPath("$.warnings[0].errorCode").value("-20104"));
      mvc.perform(
              get("/api/payroll/shadow/runs/" + runId + "/diff")
                  .header("Authorization", "Bearer " + exec))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.summary.errorRowsJava").value(1))
          .andExpect(jsonPath("$.summary.errorRowsLegacy").value(0))
          .andExpect(jsonPath("$.summary.unexplained").value(0))
          // recorded legacy pack still has emp 43 calculated; Java errored -> LEGACY_ONLY
          .andExpect(
              jsonPath("$.lines[?(@.empId == 43 && @.classification == 'LEGACY_ONLY')]")
                  .value(org.hamcrest.Matchers.hasSize(4)))
          .andExpect(
              jsonPath("$.lines[?(@.empId == 43 && @.classification == 'MATCH')]").isEmpty());
    } finally {
      jdbc.update("update salary_records set end_date = null where emp_id = 43");
    }
  }

  private long createRun() throws Exception {
    JsonNode run =
        body(
            mvc.perform(
                    json(
                        post("/api/payroll/periods/202406/runs"),
                        exec,
                        Map.of("runType", "REGULAR")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.runType").value("REGULAR"))
                .andExpect(jsonPath("$.engine").value("JAVA"))
                .andExpect(jsonPath("$.createdBy").isNotEmpty())
                .andReturn());
    assertThat(
            jdbc.queryForObject(
                "select status from pay_periods where period_id = 202406", String.class))
        .isEqualTo("PROCESSING");
    return run.path("runId").asLong();
  }

  private JsonNode awaitCalculated(long runId) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (true) {
      JsonNode st =
          body(
              mvc.perform(
                      get("/api/payroll/runs/" + runId + "/status")
                          .header("Authorization", "Bearer " + exec))
                  .andExpect(status().isOk())
                  .andReturn());
      String status = st.path("status").asText();
      if (!"CALCULATING".equals(status)) {
        assertThat(status).as(st.toString()).isEqualTo("CALCULATED");
        return st;
      }
      assertThat(Instant.now()).as("calculation did not finish").isBefore(deadline);
      Thread.sleep(200);
    }
  }

  private String token(String email) throws Exception {
    return body(mvc.perform(
                json(
                    post("/api/auth/login"), null, Map.of("username", email, "password", PASSWORD)))
            .andReturn())
        .get("accessToken")
        .asText();
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String token, Object value) throws Exception {
    request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return request;
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }

  /**
   * Level 3: tests/reconciliation/pg/vw_payroll_latest.sql on the just-approved Java run must equal
   * tests/golden/payroll/vw_payroll_latest-{period}-approved.csv (long form of views-baseline.csv).
   */
  private void assertPayrollLatestMatchesGolden(long periodId) throws Exception {
    Path root = repoRoot(periodId);
    String sql = Files.readString(root.resolve("tests/reconciliation/pg/vw_payroll_latest.sql"));
    List<String> actual = new java.util.ArrayList<>();
    int rowNo = 0;
    for (Map<String, Object> r : jdbc.queryForList(sql)) {
      rowNo++;
      for (Map.Entry<String, Object> c : r.entrySet()) {
        Object v = c.getValue();
        String text =
            v instanceof BigDecimal d
                ? (d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString())
                : String.valueOf(v);
        actual.add("VW_PAYROLL_LATEST," + rowNo + "," + c.getKey().toUpperCase() + "," + text);
      }
    }
    List<String> golden =
        Files.readAllLines(
                root.resolve(
                    "tests/golden/payroll/vw_payroll_latest-" + periodId + "-approved.csv"))
            .stream()
            .skip(1)
            .filter(l -> !l.isBlank())
            .toList();
    assertThat(actual).containsExactlyElementsOf(golden);
  }

  private static Path repoRoot(long periodId) {
    Path p = Path.of("").toAbsolutePath();
    while (p != null
        && !Files.isRegularFile(p.resolve("tests/golden/payroll/" + periodId + ".json"))) {
      p = p.getParent();
    }
    assertThat(p).as("repo root with tests/golden/payroll").isNotNull();
    return p;
  }

  private static Map<String, BigDecimal> recordedPack(long periodId) throws Exception {
    Path p = repoRoot(periodId);
    JsonNode pack =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(p.resolve("tests/golden/payroll/" + periodId + ".json").toFile());
    Map<String, BigDecimal> rows = new HashMap<>();
    for (JsonNode r : pack.path("rows")) {
      if ("ERROR".equals(r.path("status").asText())) {
        continue;
      }
      rows.put(
          r.path("empId").asLong() + ":" + r.path("elementId").asLong(),
          new BigDecimal(r.path("amount").asText()));
    }
    return rows;
  }

  private static BigDecimal sum(Map<String, BigDecimal> rows, String type) {
    BigDecimal total = BigDecimal.ZERO;
    for (Map.Entry<String, BigDecimal> e : rows.entrySet()) {
      long element = Long.parseLong(e.getKey().split(":")[1]);
      boolean earning = element == 1;
      if ("EARNING".equals(type) == earning) {
        total = total.add(e.getValue().abs());
      }
    }
    return total.setScale(2);
  }
}
