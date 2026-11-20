package com.hrms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.auth.ApiTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

class ReportApiTest extends ApiTestSupport {

    private static final long KEVIN = 31; // grade 6: REPORTS:VIEW + PAYROLL:VIEW
    private static final long MARIA = 32; // below grade 5: neither

    @BeforeEach
    void today() {
        clock.set(Instant.parse("2026-03-02T09:00:00Z"));
    }

    private ResultActions as(long empId, String url) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(empId))));
    }

    @Test
    void everyReportNeedsReportsView() throws Exception {
        for (String r : List.of("", "/pending-approvals", "/leave-summary", "/org-hierarchy",
                "/employee-compensation", "/payroll-latest")) {
            as(MARIA, "/api/v1/reports" + r).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(get("/api/v1/reports" + r)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void compensationAndPayrollAlsoNeedPayrollView() throws Exception {
        jdbc.update("""
                INSERT INTO hrms.permission_rules (rule_id, module_code, action_code, min_grade_id, rule_description,
                    created_by) VALUES (900, 'REPORTS', 'VIEW', 0, 'test', 'test')
                """);
        try {
            as(MARIA, "/api/v1/reports/org-hierarchy").andExpect(status().isOk());
            as(MARIA, "/api/v1/reports/employee-compensation").andExpect(status().isForbidden());
            as(MARIA, "/api/v1/reports/payroll-latest").andExpect(status().isForbidden());
            as(MARIA, "/api/v1/reports")
                    .andExpect(jsonPath("$[?(@.id=='EMPLOYEE_COMPENSATION')].available").value(false))
                    .andExpect(jsonPath("$[?(@.id=='LEAVE_SUMMARY')].available").value(true));
        } finally {
            jdbc.update("DELETE FROM hrms.permission_rules WHERE rule_id = 900");
        }
        as(KEVIN, "/api/v1/reports").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.id=='EMPLOYEE_COMPENSATION')].available").value(true));
    }

    @Test
    void pendingApprovalsMirrorTheViewAndFilter() throws Exception {
        long requestId = jdbc.queryForObject("""
                INSERT INTO hrms.leave_requests (emp_id, leave_type_id, start_date, end_date, total_days, status,
                    approver_emp_id, created_by) VALUES (32, 1, DATE '2026-03-10', DATE '2026-03-11', 2, 'PENDING', 31, 'test')
                RETURNING request_id
                """, Long.class);
        try {
            JsonNode all = read(as(KEVIN, "/api/v1/reports/pending-approvals").andExpect(status().isOk()));
            assertThat(all.size()).isEqualTo(
                    jdbc.queryForObject("SELECT count(*) FROM hrms.vw_pending_approvals", Integer.class));
            JsonNode mine = read(as(KEVIN, "/api/v1/reports/pending-approvals?approverId=31&approvalType=LEAVE"));
            JsonNode row = null;
            for (JsonNode n : mine) {
                assertThat(n.get("approverId").asLong()).isEqualTo(31);
                assertThat(n.get("approvalType").asText()).isEqualTo("LEAVE");
                if (n.get("itemId").asLong() == requestId) {
                    row = n;
                }
            }
            assertThat(row).isNotNull();
            assertThat(row.get("requestorName").asText()).isEqualTo("MARIA RODRIGUEZ");
            assertThat(row.get("details").asText()).isEqualTo("2 day(s) 03/10-03/11");
            as(KEVIN, "/api/v1/reports/pending-approvals?approvalType=OTHER").andExpect(status().isBadRequest());
        } finally {
            jdbc.update("DELETE FROM hrms.leave_requests WHERE request_id = ?", requestId);
        }
    }

    @Test
    void leaveSummaryReturnsPendingNetAvailableAndLegacyAvailable() throws Exception {
        jdbc.update("""
                INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, opening_balance, accrued, used,
                    adjustment, pending, created_by) VALUES (32, 2, 2026, 10, 5, 3, 1, 4, 'test')
                ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                UPDATE hrms.leave_balances SET opening_balance = 10, accrued = 5, used = 3, adjustment = 1, pending = 4
                WHERE emp_id = 32 AND leave_type_id = 2 AND calendar_year = 2026
                """);
        JsonNode rows = read(as(KEVIN, "/api/v1/reports/leave-summary?empId=32").andExpect(status().isOk()));
        JsonNode sick = null;
        for (JsonNode r : rows) {
            assertThat(r.get("calendarYear").asInt()).isEqualTo(2026);
            assertThat(r.get("available").decimalValue())
                    .isEqualByComparingTo(r.get("availableBeforePending").decimalValue()
                            .subtract(r.get("pending").decimalValue()));
            if (r.get("leaveTypeName").asText().equals(jdbc.queryForObject(
                    "SELECT leave_type_name FROM hrms.leave_types WHERE leave_type_id = 2", String.class))) {
                sick = r;
            }
        }
        assertThat(sick).isNotNull();
        assertThat(sick.get("pending").decimalValue()).isEqualByComparingTo("4");
        assertThat(sick.get("available").decimalValue()).isEqualByComparingTo("9");
        assertThat(sick.get("availableBeforePending").decimalValue()).isEqualByComparingTo("13");
        assertThat(sick.get("utilizationPct").decimalValue()).isEqualByComparingTo("20.0");
        assertThat(read(as(KEVIN, "/api/v1/reports/leave-summary?year=1999")).size()).isZero();
        // the default year follows the injected clock, not the database's CURRENT_DATE
        clock.set(Instant.parse("2025-06-01T09:00:00Z"));
        JsonNode y2025 = read(as(KEVIN, "/api/v1/reports/leave-summary?empId=32"));
        y2025.forEach(r -> assertThat(r.get("calendarYear").asInt()).isEqualTo(2025));
    }

    @Test
    void orgHierarchyWholeTreeMatchesTheView() throws Exception {
        JsonNode tree = read(as(KEVIN, "/api/v1/reports/org-hierarchy").andExpect(status().isOk()));
        assertThat(tree.size()).isEqualTo(
                jdbc.queryForObject("SELECT count(*) FROM hrms.vw_org_hierarchy", Integer.class));
        JsonNode root = tree.get(0);
        assertThat(root.get("orgLevel").asInt()).isEqualTo(1);
        assertThat(root.get("managerEmpId").isNull()).isTrue();
        as(KEVIN, "/api/v1/reports/org-hierarchy?maxDepth=2").andExpect(status().isBadRequest());
    }

    @Test
    void orgChartFromARootFollowsGetOrgChart() throws Exception {
        JsonNode sub = read(as(KEVIN, "/api/v1/reports/org-hierarchy?rootEmpId=30").andExpect(status().isOk()));
        assertThat(sub.get(0).get("empId").asLong()).isEqualTo(30);
        assertThat(sub.get(0).get("orgLevel").asInt()).isEqualTo(1);
        List<Long> ids = new ArrayList<>();
        sub.forEach(n -> ids.add(n.get("empId").asLong()));
        assertThat(ids).contains(31L, 32L);
        sub.forEach(n -> assertThat(jdbc.queryForObject(
                "SELECT employment_status FROM hrms.employees WHERE emp_id = ?", String.class,
                n.get("empId").asLong())).isEqualTo("ACTIVE"));
        JsonNode one = read(as(KEVIN, "/api/v1/reports/org-hierarchy?rootEmpId=30&maxDepth=1"));
        assertThat(one.size()).isEqualTo(1);
        JsonNode two = read(as(KEVIN, "/api/v1/reports/org-hierarchy?rootEmpId=30&maxDepth=2"));
        two.forEach(n -> assertThat(n.get("orgLevel").asInt()).isLessThanOrEqualTo(2));
        assertThat(two.get(1).get("orgPath").asText()).startsWith(" > " + sub.get(0).get("empName").asText() + " > ");
        as(KEVIN, "/api/v1/reports/org-hierarchy?rootEmpId=999999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_FOUND"));
        as(KEVIN, "/api/v1/reports/org-hierarchy?rootEmpId=30&maxDepth=0").andExpect(status().isBadRequest());
    }

    @Test
    void compensationAndPayrollLatestMirrorTheViews() throws Exception {
        JsonNode comp = read(as(KEVIN, "/api/v1/reports/employee-compensation").andExpect(status().isOk()));
        assertThat(comp.size()).isEqualTo(
                jdbc.queryForObject("SELECT count(*) FROM hrms.vw_employee_compensation", Integer.class));
        JsonNode kevin = null;
        for (JsonNode n : comp) {
            if (n.get("empId").asLong() == KEVIN) {
                kevin = n;
            }
        }
        assertThat(kevin).isNotNull();
        assertThat(kevin.get("baseSalary").decimalValue()).isEqualByComparingTo("145000");
        assertThat(kevin.get("salaryEffectiveDate").asText()).isEqualTo("2023-01-01");
        JsonNode pay = read(as(KEVIN, "/api/v1/reports/payroll-latest").andExpect(status().isOk()));
        assertThat(pay.size()).isEqualTo(
                jdbc.queryForObject("SELECT count(*) FROM hrms.vw_payroll_latest", Integer.class));
    }
}
