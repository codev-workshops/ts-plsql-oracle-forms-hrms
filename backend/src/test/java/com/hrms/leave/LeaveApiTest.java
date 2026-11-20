package com.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeaveApiTest extends LeaveTestSupport {

    String employee;
    String manager;
    String peer;
    String admin;

    @BeforeEach
    void signIn() throws Exception {
        employee = tokenFor(37);
        manager = tokenFor(31);
        peer = tokenFor(36);
        admin = tokenFor(1);
    }

    long submitPto() throws Exception {
        return read(submit(employee, days(PTO, "2026-03-02", "2026-03-06")).andExpect(status().isCreated()))
                .get("requestId").asLong();
    }

    @Test
    void submitCountsBusinessDaysByLocationAndNotifiesTheManager() throws Exception {
        submit(employee, Map.of("leaveTypeId", PTO, "startDate", "2026-03-02", "endDate", "2026-03-06",
                "reason", "trip")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalDays").value(4.0)) // 5 weekdays minus the CHI holiday
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approverEmpId").value(31))
                .andExpect(jsonPath("$.leaveTypeCode").value("PTO"))
                .andExpect(jsonPath("$.halfDay").value(false));
        assertThat(balance(37, PTO, "pending")).isEqualTo(4.0);
        assertThat(balance(37, PTO, "available")).isEqualTo(6.0);
        Map<String, Object> n = jdbc.queryForMap("SELECT * FROM hrms.notification_queue");
        assertThat(n).containsEntry("recipient_emp_id", 31L).containsEntry("status", "PENDING")
                .containsEntry("subject", "Leave Request Pending Approval")
                .containsEntry("reference_table", "LEAVE_REQUESTS");
        assertThat((String) n.get("body")).isEqualTo(
                "ALEX TAYLOR has requested 4 day(s) of Paid Time Off from 03/02/2026 to 03/06/2026.");
        assertThat(jdbc.queryForObject("SELECT new_values->>'status' FROM hrms.audit_log WHERE "
                + "table_name = 'LEAVE_REQUESTS' AND action_type = 'INSERT'", String.class)).isEqualTo("PENDING");
    }

    @Test
    void submitValidationsKeepTheLegacyCodes() throws Exception {
        expect(submit(employee, days(99, "2026-03-09", "2026-03-09")), 422, "INVALID_LEAVE_TYPE", -20203);
        jdbc.update("UPDATE hrms.leave_types SET active_flag = false WHERE leave_type_id = ?", COMP);
        expect(submit(employee, days(COMP, "2026-03-09", "2026-03-09")), 422, "INVALID_LEAVE_TYPE", -20203);
        jdbc.update("UPDATE hrms.leave_types SET active_flag = true WHERE leave_type_id = ?", COMP);
        jdbc.update("UPDATE hrms.employees SET hire_date = DATE '2025-06-01' WHERE emp_id = 37");
        try {
            expect(submit(employee, days(FMLA, "2026-03-09", "2026-03-09")), 422, "MIN_TENURE_NOT_MET", -20203);
        } finally {
            jdbc.update("UPDATE hrms.employees SET hire_date = DATE '2022-01-10' WHERE emp_id = 37");
        }
        expect(submit(employee, days(PTO, "2026-03-10", "2026-03-09")), 400, "INVALID_DATE_RANGE", -20210);
        expect(submit(employee, days(PTO, "2026-02-24", "2026-02-24")), 422, "BACKDATE_LIMIT_EXCEEDED", -20211);
        submit(employee, days(PTO, "2026-02-25", "2026-02-25")).andExpect(status().isCreated()); // 5 days back
        expect(submit(employee, days(PTO, "2026-03-07", "2026-03-08")), 422, "NO_BUSINESS_DAYS", -20212);
        expect(submit(employee, days(PTO, "2026-03-04", "2026-03-04")), 422, "NO_BUSINESS_DAYS", -20212);
        submit(employee, days(PTO, "2026-03-05", "2026-03-05")).andExpect(status().isCreated()) // HQ-only holiday
                .andExpect(jsonPath("$.totalDays").value(1.0));
        expect(submit(employee, Map.of("startDate", "2026-03-09", "endDate", "2026-03-09")), 400,
                "VALIDATION_FAILED", null);
    }

    @Test
    void overlapIsHalfDayPeriodAware() throws Exception {
        submit(employee, days(PTO, "2026-03-09", "2026-03-10")).andExpect(status().isCreated());
        expect(submit(employee, days(PTO, "2026-03-10", "2026-03-12")), 409, "LEAVE_OVERLAP", -20202);
        submit(employee, halfDay("2026-03-11", "AM")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalDays").value(0.5)).andExpect(jsonPath("$.halfDayPeriod").value("AM"));
        submit(employee, halfDay("2026-03-11", "PM")).andExpect(status().isCreated());
        expect(submit(employee, halfDay("2026-03-11", "AM")), 409, "LEAVE_OVERLAP", -20202);
        expect(submit(employee, days(PTO, "2026-03-11", "2026-03-11")), 409, "LEAVE_OVERLAP", -20202);
        expect(submit(employee, Map.of("leaveTypeId", PTO, "startDate", "2026-03-12", "endDate", "2026-03-13",
                "halfDay", true, "halfDayPeriod", "AM")), 400, "VALIDATION_FAILED", null);
        expect(submit(employee, Map.of("leaveTypeId", PTO, "startDate", "2026-03-12", "endDate", "2026-03-12",
                "halfDay", true)), 400, "VALIDATION_FAILED", null);
        expect(submit(employee, Map.of("leaveTypeId", PTO, "startDate", "2026-03-12", "endDate", "2026-03-12",
                "halfDayPeriod", "PM")), 400, "VALIDATION_FAILED", null);
        expect(submit(employee, halfDay("2026-03-14", "AM")), 422, "NO_BUSINESS_DAYS", -20212);
        // a cancelled request no longer blocks the dates
        long id = read(submit(employee, days(PTO, "2026-03-16", "2026-03-16"))).get("requestId").asLong();
        command(employee, id, "cancel", Map.of()).andExpect(status().isOk());
        submit(employee, days(PTO, "2026-03-16", "2026-03-16")).andExpect(status().isCreated());
    }

    @Test
    void balanceIsEnforcedOnlyForAccrualTypes() throws Exception {
        expect(submit(employee, days(SICK, "2026-03-09", "2026-03-16")), 422, "INSUFFICIENT_LEAVE_BALANCE", -20201)
                .andExpect(jsonPath("$.detail").value("Insufficient leave balance. Available: 5, Requested: 6"));
        submit(employee, days(SICK, "2026-03-09", "2026-03-13")).andExpect(status().isCreated());
        expect(submit(employee, halfDay("2026-03-16", "AM", SICK)), 422, "INSUFFICIENT_LEAVE_BALANCE", -20201);
        submit(employee, days(COMP, "2026-03-16", "2026-03-27")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalDays").value(10.0));
        assertThat(balance(37, COMP, "pending")).isEqualTo(10.0);
    }

    @Test
    void noApprovalTypesAreApprovedOnSubmitAndUsedDirectly() throws Exception { // DEF-12
        long id = read(submit(employee, days(JURY, "2026-03-09", "2026-03-10")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvalDate").isNotEmpty())).get("requestId").asLong();
        assertThat(balance(37, JURY, "used")).isEqualTo(2.0);
        assertThat(balance(37, JURY, "pending")).isZero();
        assertThat(jdbc.queryForList("SELECT recipient_emp_id || ':' || subject FROM hrms.notification_queue",
                String.class)).containsExactly("37:Leave Request Approved");
        assertThat(statusChanges(id)).isZero();
        command(employee, id, "cancel", Map.of("reason", "not needed")).andExpect(status().isOk());
        assertThat(balance(37, JURY, "used")).isZero();
    }

    @Test
    void approveMovesPendingToUsedAndAuditsTheStatus() throws Exception {
        long id = submitPto();
        as(manager, get("/api/v1/leave/approvals")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(id)).andExpect(jsonPath("$[0].employeeName").value("ALEX TAYLOR"));
        as(peer, get("/api/v1/leave/approvals")).andExpect(jsonPath("$.length()").value(0));
        expect(command(peer, id, "approve", Map.of()), 404, "NOT_FOUND", null);
        expect(command(employee, id, "approve", Map.of()), 403, "FORBIDDEN", null);
        command(manager, id, "approve", Map.of("comments", "enjoy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.approvalComments").value("enjoy"))
                .andExpect(jsonPath("$.approverEmpId").value(31));
        assertThat(balance(37, PTO, "pending")).isZero();
        assertThat(balance(37, PTO, "used")).isEqualTo(4.0);
        Map<String, Object> a = jdbc.queryForMap("SELECT old_values->>'status' AS o, new_values->>'status' AS n, "
                + "changed_by, session_id FROM hrms.audit_log WHERE action_type = 'STATUS_CHANGE' AND record_id = ?", id);
        assertThat(a).containsEntry("o", "PENDING").containsEntry("n", "APPROVED")
                .containsEntry("changed_by", "kevin.garcia@company.com");
        assertThat(a.get("session_id")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT body FROM hrms.notification_queue WHERE recipient_emp_id = 37",
                String.class)).isEqualTo("Your leave request from 03/02/2026 to 03/06/2026 has been approved.");
        expect(command(manager, id, "approve", Map.of()), 409, "INVALID_REQUEST_STATUS", -20204);
        expect(command(manager, id, "reject", Map.of()), 409, "INVALID_REQUEST_STATUS", -20204);

        as(manager, get("/api/v1/leave/team-calendar").param("from", "2026-03-01").param("to", "2026-03-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].requestId").value(id));
        expect(as(manager, get("/api/v1/leave/team-calendar").param("from", "2026-03-31").param("to", "2026-03-01")),
                400, "INVALID_DATE_RANGE", -20210);

        command(employee, id, "cancel", Map.of("reason", "plans changed")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("plans changed"));
        assertThat(balance(37, PTO, "used")).isZero();
        expect(command(employee, id, "cancel", Map.of()), 409, "INVALID_REQUEST_STATUS", -20204);
        assertThat(statusChanges(id)).isEqualTo(2);
    }

    @Test
    void rejectReleasesPendingAndNotifiesWithTheReason() throws Exception {
        long id = submitPto();
        command(manager, id, "reject", Map.of("comments", "busy week")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(balance(37, PTO, "pending")).isZero();
        assertThat(jdbc.queryForObject("SELECT body FROM hrms.notification_queue WHERE recipient_emp_id = 37",
                String.class)).isEqualTo("Your leave request has been rejected. Reason: busy week");
        expect(command(employee, id, "cancel", Map.of()), 409, "INVALID_REQUEST_STATUS", -20204);
    }

    @Test
    void cancelIsOwnerOnlyAndReleasesPending() throws Exception {
        long id = submitPto();
        expect(command(peer, id, "cancel", Map.of()), 404, "NOT_FOUND", null);
        expect(command(manager, id, "cancel", Map.of()), 403, "FORBIDDEN", null);
        command(employee, id, "cancel", Map.of()).andExpect(status().isOk());
        assertThat(balance(37, PTO, "pending")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.notification_queue WHERE recipient_emp_id = 37",
                Integer.class)).isZero(); // PKG-L02 parity: no notification on cancel
    }

    @Test
    void readsAreScopedToTheCaller() throws Exception {
        long id = submitPto();
        as(employee, get("/api/v1/leave/requests/" + id)).andExpect(status().isOk());
        as(manager, get("/api/v1/leave/requests/" + id)).andExpect(status().isOk());
        as(admin, get("/api/v1/leave/requests/" + id)).andExpect(status().isOk());
        expect(as(peer, get("/api/v1/leave/requests/" + id)), 404, "NOT_FOUND", null);
        expect(as(peer, get("/api/v1/leave/requests/424242")), 404, "NOT_FOUND", null);
        as(employee, get("/api/v1/leave/requests")).andExpect(jsonPath("$.length()").value(1));
        as(employee, get("/api/v1/leave/requests").param("status", "APPROVED")).andExpect(jsonPath("$.length()").value(0));
        as(employee, get("/api/v1/leave/requests").param("year", "2025")).andExpect(jsonPath("$.length()").value(0));
        as(manager, get("/api/v1/leave/requests")).andExpect(jsonPath("$.length()").value(0));
        as(employee, get("/api/v1/leave/balances")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].leaveTypeCode").value("PTO")).andExpect(jsonPath("$[0].pending").value(4.0))
                .andExpect(jsonPath("$[0].available").value(6.0));
        as(employee, get("/api/v1/leave/types")).andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[4].requiresApproval").value(false));
        mvc.perform(get("/api/v1/leave/types")).andExpect(status().isUnauthorized());
    }

    private Map<String, Object> halfDay(String date, String period) {
        return halfDay(date, period, PTO);
    }

    private Map<String, Object> halfDay(String date, String period, long type) {
        return Map.of("leaveTypeId", type, "startDate", date, "endDate", date, "halfDay", true,
                "halfDayPeriod", period);
    }

    private int statusChanges(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM hrms.audit_log WHERE table_name = 'LEAVE_REQUESTS' "
                + "AND action_type = 'STATUS_CHANGE' AND record_id = ?", Integer.class, id);
    }

    private org.springframework.test.web.servlet.ResultActions expect(
            org.springframework.test.web.servlet.ResultActions r, int http, String code, Integer legacy)
            throws Exception {
        r.andExpect(status().is(http)).andExpect(jsonPath("$.code").value(code));
        if (legacy == null) {
            r.andExpect(jsonPath("$.legacyCode").isEmpty());
        } else {
            r.andExpect(jsonPath("$.legacyCode").value(legacy));
        }
        return r;
    }
}
