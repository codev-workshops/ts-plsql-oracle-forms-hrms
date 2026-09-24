package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Level 1 for the six report queries (TEST_STRATEGY.md §5 row 5): every JSON report is compared
 * row-for-row with the Level 3 golden {@code tests/golden/views-baseline.csv} (the recorded
 * expected output of the six {@code VW_*} views on the frozen seed at 2024-06-30), plus the
 * contract-only behaviours (CSV layout, {@code Accept} negotiation, {@code WITH RECURSIVE} over a
 * terminated manager, VAL-05 {@code pending} subtraction, {@code mine=true} self scope).
 */
class ReportingApiTest extends AuthApiTestBase {

  private static final String AS_OF = "2024-06-30";

  private String exec;
  private String manager;
  private String staff;

  @BeforeEach
  void seed() throws Exception {
    HrmsPostgres.resetSchema();
    seedAccounts();
    exec = token(EXEC_EMAIL);
    manager = token(MANAGER_EMAIL);
    staff = token(STAFF_EMAIL);
  }

  // ---------------------------------------------------------------- golden row-for-row

  @Test
  void employeeDirectoryMatchesVwActiveEmployees() throws Exception {
    List<Map<String, String>> golden = golden("VW_ACTIVE_EMPLOYEES");
    JsonNode page = report("/api/reports/employee-directory", manager);
    JsonNode rows = page.get("content");
    assertThat(rows).hasSize(golden.size());
    assertThat(page.at("/page/totalElements").asLong()).isEqualTo(golden.size());
    assertThat(page.at("/summary/totalHeadcount").asInt()).isEqualTo(golden.size());
    for (int i = 0; i < golden.size(); i++) {
      Map<String, String> g = golden.get(i);
      JsonNode r = rows.get(i);
      assertThat(r.get("empId").asText()).as("row %d", i).isEqualTo(g.get("EMP_ID"));
      assertThat(r.get("empNumber").asText()).isEqualTo(g.get("EMP_NUMBER"));
      assertThat(r.get("fullName").asText()).isEqualTo(g.get("FULL_NAME"));
      assertThat(text(r, "email")).isEqualTo(g.get("EMAIL"));
      assertThat(text(r, "phoneWork")).isEqualTo(g.get("PHONE_WORK"));
      assertThat(r.get("hireDate").asText()).isEqualTo(g.get("HIRE_DATE"));
      assertNumber(r.get("tenureYears").asText(), g.get("TENURE_YEARS"));
      assertThat(r.get("tenureYears").asText()).matches("^-?[0-9]+\\.[0-9]$");
      assertThat(r.get("deptId").asText()).isEqualTo(g.get("DEPT_ID"));
      assertThat(r.get("deptName").asText()).isEqualTo(g.get("DEPT_NAME"));
      assertThat(r.get("deptCode").asText()).isEqualTo(g.get("DEPT_CODE"));
      assertThat(text(r, "costCenter")).isEqualTo(g.get("COST_CENTER"));
      assertThat(r.get("jobId").asText()).isEqualTo(g.get("JOB_ID"));
      assertThat(r.get("jobTitle").asText()).isEqualTo(g.get("JOB_TITLE"));
      assertThat(r.get("jobCode").asText()).isEqualTo(g.get("JOB_CODE"));
      assertThat(r.get("gradeId").asText()).isEqualTo(g.get("GRADE_ID"));
      assertThat(r.get("gradeName").asText()).isEqualTo(g.get("GRADE_NAME"));
      assertThat(text(r, "managerEmpId")).isEqualTo(g.get("MANAGER_EMP_ID"));
      assertThat(text(r, "managerName")).isEqualTo(g.get("MANAGER_NAME"));
      assertThat(text(r, "locationCode")).isEqualTo(g.get("LOCATION_CODE"));
      assertThat(text(r, "locationName")).isEqualTo(g.get("LOCATION_NAME"));
      assertThat(text(r, "city")).isEqualTo(g.get("CITY"));
      assertThat(text(r, "stateProvince")).isEqualTo(g.get("STATE_PROVINCE"));
    }
    // department summary: headcount per department equals the golden grouping
    Map<String, Long> byDept = new TreeMap<>();
    golden.forEach(g -> byDept.merge(g.get("DEPT_ID"), 1L, Long::sum));
    Map<String, Long> served = new TreeMap<>();
    page.at("/summary/headcountByDepartment")
        .forEach(d -> served.put(d.get("deptId").asText(), d.get("headcount").asLong()));
    assertThat(served).isEqualTo(byDept);
  }

  @Test
  void orgHierarchyMatchesVwOrgHierarchy() throws Exception {
    List<Map<String, String>> golden = golden("VW_ORG_HIERARCHY");
    JsonNode rows = report("/api/reports/org-hierarchy", manager).get("content");
    assertThat(rows).hasSize(golden.size());
    // the contract sorts by orgPath (the view: ORDER SIBLINGS BY last_name) – rows are keyed by
    // empId
    assertSortedByOrgPath(rows);
    Map<String, JsonNode> byEmp = byEmpId(rows);
    for (int i = 0; i < golden.size(); i++) {
      Map<String, String> g = golden.get(i);
      JsonNode r = byEmp.get(g.get("EMP_ID"));
      assertThat(r).as("emp %s", g.get("EMP_ID")).isNotNull();
      assertThat(r.get("empNumber").asText()).isEqualTo(g.get("EMP_NUMBER"));
      assertThat(r.get("fullName").asText()).isEqualTo(g.get("EMP_NAME"));
      assertThat(text(r, "managerEmpId")).isEqualTo(g.get("MANAGER_EMP_ID"));
      assertThat(r.get("orgLevel").asText()).isEqualTo(g.get("ORG_LEVEL"));
      assertThat(" > " + r.get("orgPath").asText()).isEqualTo(g.get("ORG_PATH"));
      assertThat(r.get("isLeaf").asBoolean()).isEqualTo("1".equals(g.get("IS_LEAF")));
      assertThat(r.get("cycle").asBoolean()).isFalse();
    }
  }

  @Test
  void employeeCompensationMatchesVwEmployeeCompensation() throws Exception {
    List<Map<String, String>> golden = golden("VW_EMPLOYEE_COMPENSATION");
    JsonNode page = report("/api/reports/employee-compensation", exec);
    JsonNode rows = page.get("content");
    assertThat(rows).hasSize(golden.size());
    for (int i = 0; i < golden.size(); i++) {
      Map<String, String> g = golden.get(i);
      JsonNode r = rows.get(i);
      assertThat(r.get("empId").asText()).as("row %d", i).isEqualTo(g.get("EMP_ID"));
      assertThat(r.get("fullName").asText()).isEqualTo(g.get("EMP_NAME"));
      assertThat(r.get("deptName").asText()).isEqualTo(g.get("DEPT_NAME"));
      assertThat(r.get("jobTitle").asText()).isEqualTo(g.get("JOB_TITLE"));
      assertNumber(r.get("baseSalary").asText(), g.get("BASE_SALARY"));
      assertNumber(r.get("minSalary").asText(), g.get("GRADE_MIN"));
      assertNumber(r.get("maxSalary").asText(), g.get("GRADE_MAX"));
      assertThat(r.get("effectiveDate").asText()).isEqualTo(g.get("SALARY_EFFECTIVE_DATE"));
      // view COMPA_RATIO is a percentage rounded to 1 decimal; contract is ROUND(ratio, 4)
      assertThat(r.get("compaRatio").asText()).matches("^-?[0-9]+\\.[0-9]{4}$");
      BigDecimal pct = new BigDecimal(r.get("compaRatio").asText()).movePointRight(2);
      assertThat(pct.subtract(new BigDecimal(g.get("COMPA_RATIO"))).abs())
          .isLessThanOrEqualTo(new BigDecimal("0.06"));
      assertThat(r.get("baseSalary").asText()).matches("^-?[0-9]+\\.[0-9]{2}$");
      assertThat(r.get("yearsInGrade").asText()).matches("^-?[0-9]+\\.[0-9]$");
    }
    BigDecimal total =
        golden.stream()
            .map(g -> new BigDecimal(g.get("BASE_SALARY")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal servedTotal = sum(page.at("/summary/byDepartment"), "totalPayroll");
    assertNumber(servedTotal.toPlainString(), total.toPlainString());
  }

  @Test
  void leaveSummaryMatchesVwLeaveSummaryAndSubtractsPending() throws Exception {
    List<Map<String, String>> golden = golden("VW_LEAVE_SUMMARY");
    JsonNode page = report("/api/reports/leave-summary", manager);
    JsonNode rows = page.get("content");
    assertThat(page.get("year").asInt()).isEqualTo(2024);
    assertThat(rows).hasSize(golden.size());
    for (int i = 0; i < golden.size(); i++) {
      Map<String, String> g = golden.get(i);
      JsonNode r = rows.get(i);
      assertThat(r.get("empId").asText()).as("row %d", i).isEqualTo(g.get("EMP_ID"));
      assertThat(r.get("empName").asText()).isEqualTo(g.get("EMP_NAME"));
      assertThat(r.get("deptName").asText()).isEqualTo(g.get("DEPT_NAME"));
      assertThat(r.get("leaveTypeName").asText()).isEqualTo(g.get("LEAVE_TYPE_NAME"));
      assertNumber(r.get("openingBalance").asText(), g.get("OPENING_BALANCE"));
      assertNumber(r.get("accrued").asText(), g.get("ACCRUED"));
      assertNumber(r.get("used").asText(), g.get("USED"));
      assertNumber(r.get("adjustment").asText(), g.get("ADJUSTMENT"));
      assertNumber(r.get("pending").asText(), g.get("PENDING"));
      // legacy view value (VAL-05 bug preserved) vs fixed available
      assertNumber(r.get("legacyAvailable").asText(), g.get("AVAILABLE"));
      BigDecimal fixed =
          new BigDecimal(r.get("legacyAvailable").asText())
              .subtract(new BigDecimal(r.get("pending").asText()));
      assertNumber(r.get("available").asText(), fixed.toPlainString());
      if (g.get("UTILIZATION_PCT") == null) {
        assertThat(r.get("utilizationPct").isNull()).isTrue();
      } else {
        assertNumber(r.get("utilizationPct").asText(), g.get("UTILIZATION_PCT"));
      }
    }
  }

  @Test
  void payrollLatestMatchesVwPayrollLatest() throws Exception {
    List<Map<String, String>> golden = golden("VW_PAYROLL_LATEST");
    JsonNode page = report("/api/reports/payroll-latest", exec);
    JsonNode rows = page.get("content");
    assertThat(rows).hasSize(golden.size());
    for (int i = 0; i < golden.size(); i++) {
      Map<String, String> g = golden.get(i);
      JsonNode r = rows.get(i);
      assertThat(r.get("empId").asText()).as("row %d", i).isEqualTo(g.get("EMP_ID"));
      assertThat(r.get("empName").asText()).isEqualTo(g.get("EMP_NAME"));
      assertThat(r.get("periodName").asText()).isEqualTo(g.get("PERIOD_NAME"));
      assertNumber(r.get("grossPay").asText(), g.get("GROSS_PAY"));
      assertNumber(r.get("totalTaxes").asText(), g.get("TOTAL_TAXES"));
      assertNumber(r.get("totalDeductions").asText(), g.get("TOTAL_DEDUCTIONS"));
      assertNumber(r.get("netPay").asText(), g.get("NET_PAY"));
      assertThat(r.get("runStatus").asText()).isIn("APPROVED", "PAID");
    }
    assertThat(page.at("/summary/employeeCount").asInt()).isEqualTo(golden.size());
    assertNumber(
        page.at("/summary/totalGross").asText(),
        golden.stream()
            .map(g -> new BigDecimal(g.get("GROSS_PAY")))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .toPlainString());
  }

  @Test
  void pendingApprovalsMatchesVwPendingApprovals() throws Exception {
    List<Map<String, String>> golden = golden("VW_PENDING_APPROVALS");
    JsonNode page = report("/api/reports/pending-approvals", exec);
    JsonNode rows = page.get("content");
    assertThat(rows).hasSize(golden.size());
    Map<String, Map<String, String>> byItem = new LinkedHashMap<>();
    // view APPROVAL_TYPE is LEAVE | PERFORMANCE; the contract itemType is LEAVE | REVIEW
    golden.forEach(
        g ->
            byItem.put(
                ("LEAVE".equals(g.get("APPROVAL_TYPE")) ? "LEAVE" : "REVIEW")
                    + ":"
                    + g.get("ITEM_ID"),
                g));
    int leave = 0;
    int review = 0;
    for (JsonNode r : rows) {
      Map<String, String> g = byItem.remove(r.get("itemType").asText() + ":" + r.get("itemId"));
      assertThat(g).as("unexpected item %s", r).isNotNull();
      assertThat(r.get("empName").asText()).isEqualTo(g.get("REQUESTOR_NAME"));
      assertThat(text(r, "approverEmpId")).isEqualTo(g.get("APPROVER_ID"));
      assertThat(r.get("submittedDate").asText()).isEqualTo(g.get("REQUEST_DATE"));
      if ("LEAVE".equals(r.get("itemType").asText())) {
        leave++;
        assertThat(r.get("detail").asText()).startsWith(g.get("ITEM_DESCRIPTION"));
      } else {
        review++;
        // view: 'Performance Review - {cycleName}'; contract detail: '{cycleName}'
        assertThat(g.get("ITEM_DESCRIPTION")).endsWith(r.get("detail").asText());
      }
    }
    assertThat(byItem).isEmpty();
    assertThat(page.at("/summary/leave").asInt()).isEqualTo(leave);
    assertThat(page.at("/summary/review").asInt()).isEqualTo(review);
  }

  // ---------------------------------------------------------------- contract-only behaviour

  @Test
  void orgHierarchyWalksTerminatedManagersLikeOracleConnectBy() throws Exception {
    Path scenario =
        HrmsPostgres.repoRoot().resolve("tests/golden/scenarios/terminated-mid-manager.sql");
    for (String stmt : HrmsPostgres.splitStatements(Files.readString(scenario))) {
      jdbc.execute(stmt);
    }
    List<Map<String, String>> golden =
        goldenFrom("views-terminated-mid-manager.csv", "VW_ORG_HIERARCHY");
    JsonNode rows = report("/api/reports/org-hierarchy", manager).get("content");
    assertThat(rows).hasSize(golden.size());
    assertSortedByOrgPath(rows);
    Map<String, JsonNode> byEmp = byEmpId(rows);
    assertThat(byEmp).doesNotContainKey("21").containsKeys("22", "23", "24");
    assertThat(byEmp.get("22").get("orgLevel").asInt()).isEqualTo(5);
    assertThat(byEmp.get("22").get("orgPath").asText()).contains("> JENNIFER PARK >");
    assertThat(byEmp.get("20").get("isLeaf").asBoolean()).isFalse();
    for (int i = 0; i < golden.size(); i++) {
      Map<String, String> g = golden.get(i);
      JsonNode r = byEmp.get(g.get("EMP_ID"));
      assertThat(r).as("emp %s", g.get("EMP_ID")).isNotNull();
      assertThat(r.get("orgLevel").asText()).isEqualTo(g.get("ORG_LEVEL"));
      assertThat(" > " + r.get("orgPath").asText()).isEqualTo(g.get("ORG_PATH"));
      assertThat(r.get("isLeaf").asBoolean()).isEqualTo("1".equals(g.get("IS_LEAF")));
    }
    // sub-tree + depth cap
    JsonNode sub =
        report("/api/reports/org-hierarchy?rootEmpId=20&maxLevel=1", manager).get("content");
    assertThat(sub).hasSize(1);
    assertThat(sub.get(0).get("empId").asLong()).isEqualTo(20);
    // terminated root → -20001
    mvc.perform(
            get("/api/reports/org-hierarchy?rootEmpId=21")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("-20001"));
  }

  @Test
  void csvAliasAndAcceptNegotiation() throws Exception {
    MvcResult csv =
        mvc.perform(
                get("/api/reports/employee-directory.csv?asOf=" + AS_OF)
                    .header("Authorization", "Bearer " + manager))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
            .andExpect(header().exists("Content-Disposition"))
            .andReturn();
    String body = csv.getResponse().getContentAsString(StandardCharsets.UTF_8);
    String[] lines = body.split("\r\n");
    assertThat(lines[0]).startsWith("empId,empNumber,firstName,lastName,fullName,email");
    assertThat(lines).hasSize(1 + golden("VW_ACTIVE_EMPLOYEES").size());
    assertThat(body).endsWith("\r\n").doesNotContain("\n\n");
    assertThat(lines[1]).startsWith("1,EMP-000001,JAMES,RICHARDSON,JAMES RICHARDSON,");

    MvcResult viaAccept =
        mvc.perform(
                get("/api/reports/employee-directory?asOf=" + AS_OF)
                    .accept("text/csv")
                    .header("Authorization", "Bearer " + manager))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
            .andReturn();
    assertThat(viaAccept.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);

    mvc.perform(
            get("/api/reports/employee-directory")
                .accept("application/xml")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotAcceptable())
        .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));

    // leave-summary CSV never carries the JSON-only legacyAvailable column
    String leaveCsv =
        mvc.perform(
                get("/api/reports/leave-summary.csv?asOf=" + AS_OF)
                    .header("Authorization", "Bearer " + manager))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(leaveCsv.split("\r\n")[0])
        .doesNotContain("legacyAvailable")
        .endsWith(",utilizationPct");
  }

  @Test
  void pagingAndValidation() throws Exception {
    JsonNode page = report("/api/reports/employee-directory?page=1&size=10", manager);
    assertThat(page.get("content")).hasSize(10);
    assertThat(page.at("/page/page").asInt()).isEqualTo(1);
    assertThat(page.at("/page/size").asInt()).isEqualTo(10);
    assertThat(page.at("/page/totalPages").asInt()).isEqualTo(3);
    assertThat(page.get("content").get(0).get("empId").asLong()).isGreaterThan(10);
    JsonNode dflt =
        body(
            mvc.perform(
                    get("/api/reports/employee-directory")
                        .header("Authorization", "Bearer " + manager))
                .andReturn());
    assertThat(dflt.at("/page/size").asInt()).isEqualTo(50);
    mvc.perform(
            get("/api/reports/employee-directory?size=201")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    JsonNode dept = report("/api/reports/employee-directory?deptId=20", manager);
    assertThat(dept.get("content")).hasSize(6).allMatch(r -> r.get("deptId").asInt() == 20);
    mvc.perform(
            get("/api/reports/employee-directory?deptId=2")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20003"));
  }

  @Test
  void authoritiesFollowTheContract() throws Exception {
    mvc.perform(get("/api/reports/employee-directory").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    // compensation and payroll need PAYROLL:VIEW too – the manager has it, staff does not
    mvc.perform(
            get("/api/reports/employee-compensation").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk());
    mvc.perform(get("/api/reports/payroll-latest").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    // mine=true is the self scope: staff (emp 2) sees only what waits on them
    JsonNode mine = report("/api/reports/pending-approvals?mine=true", staff);
    assertThat(mine.get("content")).allMatch(r -> r.get("approverEmpId").asLong() == 2);
    JsonNode mgrMine = report("/api/reports/pending-approvals?mine=true", manager);
    assertThat(mgrMine.get("content"))
        .isNotEmpty()
        .allMatch(r -> r.get("approverEmpId").asLong() == 21);
    mvc.perform(get("/api/reports/pending-approvals").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    JsonNode leaveOnly = report("/api/reports/pending-approvals?itemType=LEAVE", exec);
    assertThat(leaveOnly.get("content")).allMatch(r -> "LEAVE".equals(r.get("itemType").asText()));
  }

  // ---------------------------------------------------------------- helpers

  private JsonNode report(String path, String token) throws Exception {
    String url = path + (path.contains("?") ? "&" : "?") + "asOf=" + AS_OF + "&size=200";
    return body(
        mvc.perform(get(url).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
            .andReturn());
  }

  private static Map<String, JsonNode> byEmpId(JsonNode rows) {
    Map<String, JsonNode> m = new LinkedHashMap<>();
    rows.forEach(r -> m.put(r.get("empId").asText(), r));
    return m;
  }

  private static void assertSortedByOrgPath(JsonNode rows) {
    List<String> paths = new ArrayList<>();
    rows.forEach(r -> paths.add(r.get("orgPath").asText()));
    assertThat(paths).isSorted();
  }

  private static String text(JsonNode r, String field) {
    JsonNode n = r.get(field);
    return n == null || n.isNull() ? null : n.asText();
  }

  private static void assertNumber(String actual, String golden) {
    assertThat(new BigDecimal(actual)).isEqualByComparingTo(new BigDecimal(golden));
  }

  private static BigDecimal sum(JsonNode rows, String field) {
    BigDecimal total = BigDecimal.ZERO;
    for (JsonNode r : rows) {
      total = total.add(new BigDecimal(r.get(field).asText()));
    }
    return total;
  }

  static List<Map<String, String>> golden(String view) throws Exception {
    return goldenFrom("views-baseline.csv", view);
  }

  /** tests/golden/*.csv is (view,row_no,column,value) long format; \N is SQL NULL. */
  static List<Map<String, String>> goldenFrom(String file, String view) throws Exception {
    Path csv = HrmsPostgres.repoRoot().resolve("tests/golden").resolve(file);
    TreeMap<Integer, Map<String, String>> rows = new TreeMap<>();
    for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
      String[] f = line.split(",", 4);
      if (!f[0].equals(view)) {
        continue;
      }
      rows.computeIfAbsent(Integer.parseInt(f[1]), k -> new LinkedHashMap<>())
          .put(f[2], "\\N".equals(f[3]) ? null : f[3]);
    }
    return new ArrayList<>(rows.values());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token(String email) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    return body(r).get("accessToken").asText();
  }
}
