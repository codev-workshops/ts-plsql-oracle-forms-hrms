package com.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Leave pilot end to end over HTTP. Seed: Thomas Baker (22, HQ) reports to Jennifer Park (21);
 * PTO (type 1) needs approval and accrues; Jury Duty (type 5) is auto-approved.
 * Clock fixed at Thursday 2026-11-19.
 */
class LeaveApiTest extends IntegrationTest {

    private String employee;
    private String manager;

    @BeforeEach
    void setUp() throws Exception {
        employee = login("thomas.baker@company.com");
        manager = login("jennifer.park@company.com");
    }

    private Map<String, Object> request(int type, String start, String end) {
        Map<String, Object> body = new HashMap<>();
        body.put("leaveTypeId", type);
        body.put("startDate", start);
        body.put("endDate", end);
        body.put("reason", "test");
        return body;
    }

    private JsonNode submit(Map<String, Object> body) throws Exception {
        return read(callJson(post("/api/v1/leave/requests"), employee, body).andExpect(status().isCreated()));
    }

    private BigDecimal balanceField(int type, String field) {
        return jdbc.queryForObject("SELECT " + field + " FROM leave_balances WHERE emp_id = 22 AND leave_type_id = ?"
                + " AND calendar_year = 2026", BigDecimal.class, type);
    }

    @Test
    void submitApproveFlowMovesPendingToUsed() throws Exception {
        BigDecimal availableBefore = balanceField(1, "available");
        JsonNode created = submit(request(1, "2026-12-07", "2026-12-11"));
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("totalDays").decimalValue()).isEqualByComparingTo("5");
        assertThat(created.get("approverEmpId").asLong()).isEqualTo(21);
        assertThat(balanceField(1, "pending")).isEqualByComparingTo("5");
        assertThat(balanceField(1, "available")).isEqualByComparingTo(availableBefore.subtract(BigDecimal.valueOf(5)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_queue WHERE recipient_emp_id = 21",
                Integer.class)).isEqualTo(1);

        long id = created.get("requestId").asLong();
        call(get("/api/v1/leave/approvals"), manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(id));
        callJson(post("/api/v1/leave/requests/" + id + "/approve"), manager, Map.of("comments", "ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approverName").value("JENNIFER PARK"));
        assertThat(balanceField(1, "pending")).isEqualByComparingTo("0");
        assertThat(balanceField(1, "used")).isEqualByComparingTo("5");

        call(get("/api/v1/leave/team-calendar").param("startDate", "2026-12-01").param("endDate", "2026-12-31"),
                manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE table_name = 'LEAVE_REQUESTS'"
                + " AND record_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void holidaysAreExcludedAndHalfDayCountsHalf() throws Exception {
        JsonNode thanksgiving = submit(request(1, "2026-11-23", "2026-11-29"));
        assertThat(thanksgiving.get("totalDays").decimalValue()).isEqualByComparingTo("3");
        Map<String, Object> half = request(1, "2026-12-14", "2026-12-14");
        half.put("halfDay", true);
        half.put("halfDayPeriod", "AM");
        JsonNode halfDay = submit(half);
        assertThat(halfDay.get("totalDays").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(halfDay.get("halfDayPeriod").asText()).isEqualTo("AM");
        call(get("/api/v1/leave/business-days").param("startDate", "2026-11-23").param("endDate", "2026-11-29"),
                employee).andExpect(jsonPath("$.businessDays").value(3));
    }

    @Test
    void rejectReleasesPendingDays() throws Exception {
        long id = submit(request(1, "2026-12-07", "2026-12-08")).get("requestId").asLong();
        callJson(post("/api/v1/leave/requests/" + id + "/reject"), manager, Map.of("comments", "busy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(balanceField(1, "pending")).isEqualByComparingTo("0");
        assertThat(balanceField(1, "used")).isEqualByComparingTo("0");
    }

    @Test
    void cancelRestoresPendingOrUsed() throws Exception {
        long pendingId = submit(request(1, "2026-12-07", "2026-12-08")).get("requestId").asLong();
        callJson(post("/api/v1/leave/requests/" + pendingId + "/cancel"), employee, Map.of("reason", "plans changed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(balanceField(1, "pending")).isEqualByComparingTo("0");

        long approvedId = submit(request(1, "2026-12-09", "2026-12-09")).get("requestId").asLong();
        callJson(post("/api/v1/leave/requests/" + approvedId + "/approve"), manager, Map.of());
        callJson(post("/api/v1/leave/requests/" + approvedId + "/cancel"), employee, Map.of("reason", "sick"))
                .andExpect(status().isOk());
        assertThat(balanceField(1, "used")).isEqualByComparingTo("0");

        callJson(post("/api/v1/leave/requests/" + approvedId + "/cancel"), employee, Map.of("reason", "again"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("-20204"));
    }

    @Test
    void autoApprovedTypeSettlesDirectlyToUsed() throws Exception {
        JsonNode jury = submit(request(5, "2026-12-02", "2026-12-03"));
        assertThat(jury.get("status").asText()).isEqualTo("APPROVED");
        assertThat(jury.get("approvalComments").asText()).isEqualTo("Auto-approved");
        assertThat(balanceField(5, "used")).isEqualByComparingTo("2");
        assertThat(balanceField(5, "pending")).isEqualByComparingTo("0");
    }

    @Test
    void businessRuleErrorsKeepLegacyCodes() throws Exception {
        callJson(post("/api/v1/leave/requests"), employee, request(1, "2026-12-10", "2026-12-07"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("-20210"));
        callJson(post("/api/v1/leave/requests"), employee, request(1, "2026-11-10", "2026-11-10"))
                .andExpect(jsonPath("$.code").value("-20211"));
        callJson(post("/api/v1/leave/requests"), employee, request(1, "2026-12-05", "2026-12-06"))
                .andExpect(jsonPath("$.code").value("-20212"));
        callJson(post("/api/v1/leave/requests"), employee, request(1, "2026-12-01", "2026-12-31"))
                .andExpect(jsonPath("$.code").value("-20201"));
        callJson(post("/api/v1/leave/requests"), employee, request(99, "2026-12-01", "2026-12-01"))
                .andExpect(jsonPath("$.code").value("-20203"));
        submit(request(1, "2026-12-07", "2026-12-08"));
        callJson(post("/api/v1/leave/requests"), employee, request(2, "2026-12-08", "2026-12-09"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("-20202"));
        callJson(post("/api/v1/leave/requests"), employee, Map.of("startDate", "2026-12-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("-20000"));
    }

    @Test
    void backdatingWithinFiveDaysIsAllowed() throws Exception {
        JsonNode r = submit(request(2, "2026-11-16", "2026-11-16"));
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void tenureRuleBlocksNewHires() throws Exception {
        jdbc.update("UPDATE employees SET hire_date = DATE '2026-10-01' WHERE emp_id = 22");
        callJson(post("/api/v1/leave/requests"), employee, request(3, "2026-12-07", "2026-12-07"))
                .andExpect(jsonPath("$.code").value("-20203"));
    }

    @Test
    void onlyTheManagerMayDecideAndNeverOnOwnRequest() throws Exception {
        long id = submit(request(1, "2026-12-07", "2026-12-07")).get("requestId").asLong();
        String peer = login(peerEmail());
        callJson(post("/api/v1/leave/requests/" + id + "/approve"), peer, Map.of())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("-20205"));
        callJson(post("/api/v1/leave/requests/" + id + "/approve"), employee, Map.of())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("-20205"));
        call(get("/api/v1/leave/requests/" + id), peer).andExpect(status().isForbidden());
        call(get("/api/v1/leave/requests/" + id), manager).andExpect(status().isOk());
    }

    private String peerEmail() {
        return jdbc.queryForObject("SELECT email FROM employees WHERE emp_id = 23", String.class);
    }

    @Test
    void myRequestsAndBalancesAreScopedToCaller() throws Exception {
        submit(request(1, "2026-12-07", "2026-12-07"));
        call(get("/api/v1/leave/requests"), employee).andExpect(jsonPath("$.length()").value(1));
        call(get("/api/v1/leave/requests"), manager).andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/leave/balances").param("year", "2026"), employee)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].leaveTypeCode").value("PTO"))
                .andExpect(jsonPath("$[0].pending").value(1.0));
        call(get("/api/v1/leave/types"), employee).andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    void batchOperationsAreIdempotentAndAdminOnly() throws Exception {
        callJson(post("/api/v1/leave/admin/accruals"), employee, Map.of("accrualDate", "2026-11-30"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("-20304"));
        String admin = login("james.richardson@company.com");
        BigDecimal before = balanceField(1, "accrued");
        JsonNode first = read(callJson(post("/api/v1/leave/admin/accruals"), admin,
                Map.of("accrualDate", "2026-11-30")).andExpect(status().isOk()));
        assertThat(first.get("rowsAffected").asInt()).isGreaterThan(0);
        assertThat(balanceField(1, "accrued")).isEqualByComparingTo(before.add(new BigDecimal("1.25")));
        JsonNode second = read(callJson(post("/api/v1/leave/admin/accruals"), admin,
                Map.of("accrualDate", "2026-11-30")));
        assertThat(second.get("rowsAffected").asInt()).isZero();

        callJson(post("/api/v1/leave/admin/carryover"), admin, Map.of("year", 2026)).andExpect(status().isOk());
        BigDecimal carried = jdbc.queryForObject("SELECT carryover_from_prev FROM leave_balances WHERE emp_id = 22"
                + " AND leave_type_id = 1 AND calendar_year = 2027", BigDecimal.class);
        assertThat(carried).isEqualByComparingTo("5");
        jdbc.update("UPDATE leave_balances SET carryover_expiry_dt = DATE '2026-11-01' WHERE calendar_year = 2027");
        call(post("/api/v1/leave/admin/carryover/expire"), admin).andExpect(status().isOk());
        JsonNode again = read(call(post("/api/v1/leave/admin/carryover/expire"), admin));
        assertThat(again.get("rowsAffected").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT adjustment FROM leave_balances WHERE emp_id = 22"
                + " AND leave_type_id = 1 AND calendar_year = 2027", BigDecimal.class)).isEqualByComparingTo("-5");

        callJson(post("/api/v1/leave/admin/balances/initialize"), admin, Map.of("empId", 22, "year", 2028))
                .andExpect(jsonPath("$.rowsAffected").value(6));
    }
}
