package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Level 1 for contracts/p2-leave end to end on the frozen seed with the clock pinned to Monday
 * 2024-07-01 (the seed year): every -202xx / -2021x code and its message, every state transition
 * with its balance mutation, JWT scoping, the audit_log / notification_queue side effects and the
 * BUG-05 / BUG-06 declared differences. Seed graph: emp 2 STAFF (manager emp 1 EXEC, PTO 9.5
 * available), emp 21 MANAGER (designated approver of 1001 emp 22 / 1003 emp 23), emp 11 STAFF
 * (unrelated), emp 1 EXEC (LEAVE:APPROVE + LEAVE:VIEW_ALL).
 */
@Import(LeaveApiTest.FixedClock.class)
class LeaveApiTest extends AuthApiTestBase {

  static final Instant NOW = Instant.parse("2024-07-01T09:00:00Z");

  @TestConfiguration
  static class FixedClock {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  private static final String OTHER_STAFF_EMAIL = "david.martinez@company.com"; // emp 11

  private String exec;
  private String manager;
  private String staff;
  private String otherStaff;

  @BeforeEach
  void seed() throws Exception {
    seedAccounts();
    jdbc.update("delete from notification_queue");
    jdbc.update("delete from leave_accrual_log");
    jdbc.update("delete from leave_requests where request_id not between 1001 and 1005");
    jdbc.update("update leave_requests set status = 'PENDING' where request_id in (1001, 1003)");
    jdbc.update(
        "update leave_balances set opening_balance = 5, accrued = 7.5, used = 3, adjustment = 0,"
            + " pending = 0 where balance_id = 9001");
    jdbc.update(
        "update leave_balances set opening_balance = 3, accrued = 7.5, used = 2, adjustment = 0,"
            + " pending = 5 where balance_id = 9007");
    exec = token(EXEC_EMAIL);
    manager = token(MANAGER_EMAIL);
    staff = token(STAFF_EMAIL);
    otherStaff = token(OTHER_STAFF_EMAIL);
  }

  // ------------------------------------------------------------------ submit

  @Test
  void submitPendingHappyPathMutatesPendingAuditsAndNotifiesManager() throws Exception {
    // Mon 2024-07-01 .. Fri 2024-07-05 includes Independence Day (Thu) -> 4 business days
    MvcResult r =
        mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-07-01", "2024-07-05")))
            .andExpect(status().isCreated())
            .andExpect(
                header()
                    .string("Location", org.hamcrest.Matchers.startsWith("/api/leave/requests/")))
            .andExpect(jsonPath("$.empId").value(2))
            .andExpect(jsonPath("$.empName").value("SARAH CHEN"))
            .andExpect(jsonPath("$.leaveTypeCode").value("PTO"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.totalDays").value(4))
            .andExpect(jsonPath("$.halfDay").value(false))
            .andExpect(jsonPath("$.halfDayPeriod").value((Object) null))
            .andExpect(jsonPath("$.approverEmpId").value(1))
            .andExpect(jsonPath("$.approverName").value("JAMES RICHARDSON"))
            .andExpect(jsonPath("$.approvalDate").value((Object) null))
            .andExpect(jsonPath("$.reason").value("Trip"))
            .andReturn();
    long id = body(r).get("requestId").asLong();
    assertThat(balance(9001, "pending")).isEqualByComparingTo("4");
    assertThat(balance(9001, "available")).isEqualByComparingTo("5.5");
    assertThat(auditCount("LEAVE_REQUESTS", id, "INSERT")).isEqualTo(1);
    assertThat(notificationCount(1, "Leave Request Pending Approval")).isEqualTo(1);
    assertThat(notificationBody(1, "Leave Request Pending Approval"))
        .isEqualTo(
            "SARAH CHEN has requested 4 day(s) of Paid Time Off from 07/01/2024 to 07/05/2024.");

    // GET by id: owner, approver and VIEW_ALL ok; unrelated staff -> 403
    mvc.perform(get("/api/leave/requests/" + id).header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk());
    mvc.perform(get("/api/leave/requests/" + id).header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk());
    mvc.perform(get("/api/leave/requests/" + id).header("Authorization", "Bearer " + otherStaff))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    // listing
    mvc.perform(
            get("/api/leave/requests/mine")
                .param("status", "PENDING")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].requestId").value(id));
    mvc.perform(
            get("/api/leave/requests/mine")
                .param("status", "BOGUS")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("status"));
    mvc.perform(
            get("/api/leave/requests")
                .param("empId", "2")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/leave/requests")
                .param("empId", "2")
                .param("size", "1")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(1))
        .andExpect(jsonPath("$.content[0].requestId").value(id));
    mvc.perform(
            get("/api/leave/requests")
                .param("empId", "424242")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("-20001"));
  }

  @Test
  void submitAutoApprovedTypeIsApprovedImmediatelyAndNotifiesEmployee() throws Exception {
    // JURY (5) requires no approval and has no accrual -> no balance check, used += days
    MvcResult r =
        mvc.perform(json(post("/api/leave/requests"), staff, body(5, "2024-07-08", "2024-07-09")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.totalDays").value(2))
            .andExpect(jsonPath("$.approvalDate").isNotEmpty())
            .andReturn();
    long id = body(r).get("requestId").asLong();
    assertThat(auditCount("LEAVE_REQUESTS", id, "INSERT")).isEqualTo(1);
    assertThat(notificationCount(2, "Leave Request Approved")).isEqualTo(1);
    assertThat(notificationBody(2, "Leave Request Approved"))
        .isEqualTo("Your leave request from 07/08/2024 to 07/09/2024 has been approved.");
    assertThat(notificationCount(1, "Leave Request Pending Approval")).isZero();
    // no JURY balance row for emp 2 -> QUIRK-02 no-op
    assertThat(
            jdbc.queryForObject(
                "select count(*) from leave_balances where emp_id = 2 and leave_type_id = 5",
                Integer.class))
        .isZero();
  }

  @Test
  void submitErrorCodesInContractOrder() throws Exception {
    // authority before body: manager token has LEAVE:CREATE, exec has it too; no token -> 401
    mvc.perform(
            post("/api/leave/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(1, "2024-07-08", "2024-07-09"))))
        .andExpect(status().isUnauthorized());

    // Bean validation
    Map<String, Object> missing = body(1, "2024-07-08", "2024-07-09");
    missing.remove("leaveTypeId");
    mvc.perform(json(post("/api/leave/requests"), staff, missing))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("leaveTypeId"));

    // -20210 date order (400)
    mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-07-09", "2024-07-08")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20210"))
        .andExpect(jsonPath("$.message").value("Start date must be before or equal to end date"))
        .andExpect(jsonPath("$.field").value("endDate"));

    // -20203 invalid leave type
    mvc.perform(json(post("/api/leave/requests"), staff, body(99, "2024-07-08", "2024-07-09")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20203"))
        .andExpect(jsonPath("$.message").value("Invalid leave type: 99"))
        .andExpect(jsonPath("$.field").value("leaveTypeId"));

    // -20203 tenure: hire emp 2 60 days ago, FMLA (4) needs 365
    jdbc.update("update employees set hire_date = date '2024-05-02' where emp_id = 2");
    mvc.perform(json(post("/api/leave/requests"), staff, body(4, "2024-07-08", "2024-07-09")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20203"))
        .andExpect(
            jsonPath("$.message")
                .value("Minimum tenure of 365 days not met for leave type: Family Medical Leave"));
    jdbc.update("update employees set hire_date = date '2012-06-01' where emp_id = 2");

    // -20211 more than 5 days in the past (2024-06-25 is 6 days before 07-01)
    mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-06-25", "2024-06-26")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20211"))
        .andExpect(
            jsonPath("$.message")
                .value("Cannot submit leave requests more than 5 days in the past"))
        .andExpect(jsonPath("$.field").value("startDate"));
    // exactly 5 days back is allowed (-> later rules)
    mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-06-26", "2024-06-26")))
        .andExpect(status().isCreated());

    // -20212 weekend only / BUG-05: Independence Day 2026-07-04 is a Saturday -> observed Fri 07-03
    mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-07-06", "2024-07-07")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20212"))
        .andExpect(
            jsonPath("$.message").value("Leave request must include at least one business day"));
    jdbc.update(
        "insert into holidays (holiday_id, holiday_name, holiday_date, active_flag, created_by)"
            + " values (901, 'Sat Holiday', date '2024-07-13', 'Y', 't')");
    mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-07-12", "2024-07-12")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20212"));
    jdbc.update("delete from holidays where holiday_id = 901");

    // -20202 overlap with the request created above (06-26), incl. touching a PENDING one
    mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-06-26", "2024-06-27")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20202"))
        .andExpect(jsonPath("$.message").value("Leave request overlaps with existing request"));

    // -20201 insufficient balance: available now 9.5 - 1 pending = 8.5 ; ask for 10 days
    mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-07-15", "2024-07-26")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20201"))
        .andExpect(
            jsonPath("$.message")
                .value("Insufficient leave balance. Available: 8.5, Requested: 10"));
    // half-day fractional rendering (.5) – set available to 0.25
    jdbc.update("update leave_balances set adjustment = -8.25 where balance_id = 9001");
    Map<String, Object> half = body(1, "2024-07-15", "2024-07-15");
    half.put("halfDay", true);
    half.put("halfDayPeriod", "AM");
    mvc.perform(json(post("/api/leave/requests"), staff, half))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20201"))
        .andExpect(
            jsonPath("$.message")
                .value("Insufficient leave balance. Available: .25, Requested: .5"));
  }

  @Test
  void halfDayRulesAndBug06AmPmPair() throws Exception {
    Map<String, Object> noPeriod = body(1, "2024-07-15", "2024-07-15");
    noPeriod.put("halfDay", true);
    mvc.perform(json(post("/api/leave/requests"), staff, noPeriod))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("halfDayPeriod"));

    Map<String, Object> twoDays = body(1, "2024-07-15", "2024-07-16");
    twoDays.put("halfDay", true);
    twoDays.put("halfDayPeriod", "AM");
    mvc.perform(json(post("/api/leave/requests"), staff, twoDays))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("endDate"));

    Map<String, Object> am = body(1, "2024-07-15", "2024-07-15");
    am.put("halfDay", true);
    am.put("halfDayPeriod", "AM");
    mvc.perform(json(post("/api/leave/requests"), staff, am))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.totalDays").value(0.5))
        .andExpect(jsonPath("$.halfDay").value(true))
        .andExpect(jsonPath("$.halfDayPeriod").value("AM"));
    Map<String, Object> pm = body(1, "2024-07-15", "2024-07-15");
    pm.put("halfDay", true);
    pm.put("halfDayPeriod", "PM");
    mvc.perform(json(post("/api/leave/requests"), staff, pm))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.halfDayPeriod").value("PM"));
    // second PM and a full day on that date still overlap
    mvc.perform(json(post("/api/leave/requests"), staff, pm))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20202"));
    mvc.perform(json(post("/api/leave/requests"), staff, body(1, "2024-07-15", "2024-07-15")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20202"));
    assertThat(balance(9001, "pending")).isEqualByComparingTo("1");
    // half day on a holiday -> -20212
    Map<String, Object> holiday = body(1, "2024-07-04", "2024-07-04");
    holiday.put("halfDay", true);
    holiday.put("halfDayPeriod", "AM");
    mvc.perform(json(post("/api/leave/requests"), staff, holiday))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20212"));
  }

  // ------------------------------------------------------------------ cancel

  @Test
  void cancelPendingRestoresPendingAndApprovedRestoresUsed() throws Exception {
    long id =
        body(mvc.perform(
                    json(post("/api/leave/requests"), staff, body(1, "2024-07-08", "2024-07-09")))
                .andReturn())
            .get("requestId")
            .asLong();
    assertThat(balance(9001, "pending")).isEqualByComparingTo("2");

    // approver / unrelated may not cancel
    mvc.perform(
            post("/api/leave/requests/" + id + "/cancel").header("Authorization", "Bearer " + exec))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            post("/api/leave/requests/424242/cancel").header("Authorization", "Bearer " + staff))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Leave request not found"));

    // PENDING -> CANCELLED without body: default reason
    mvc.perform(
            post("/api/leave/requests/" + id + "/cancel")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.reason").value("Cancelled by employee"));
    assertThat(balance(9001, "pending")).isEqualByComparingTo("0");
    assertThat(balance(9001, "used")).isEqualByComparingTo("3");
    assertThat(auditCount("LEAVE_REQUESTS", id, "STATUS_CHANGE")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notification_queue where reference_id = ?"
                    + " and reference_table = 'LEAVE_REQUESTS' and subject not like '%Pending%'",
                Integer.class, id))
        .isZero();

    // -20204 cancel again
    mvc.perform(
            post("/api/leave/requests/" + id + "/cancel")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20204"))
        .andExpect(jsonPath("$.message").value("Cannot cancel request in status: CANCELLED"));

    // APPROVED -> CANCELLED restores used
    long id2 =
        body(mvc.perform(
                    json(post("/api/leave/requests"), staff, body(1, "2024-07-10", "2024-07-10")))
                .andReturn())
            .get("requestId")
            .asLong();
    mvc.perform(
            post("/api/leave/requests/" + id2 + "/approve")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk());
    assertThat(balance(9001, "used")).isEqualByComparingTo("4");
    assertThat(balance(9001, "pending")).isEqualByComparingTo("0");
    mvc.perform(
            json(
                post("/api/leave/requests/" + id2 + "/cancel"),
                staff,
                Map.of("reason", "Plans changed")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.reason").value("Plans changed"));
    assertThat(balance(9001, "used")).isEqualByComparingTo("3");
    assertThat(balance(9001, "available")).isEqualByComparingTo("9.5");
  }

  // ------------------------------------------------------------------ approve / reject

  @Test
  void approveByDesignatedApproverMovesPendingToUsedAuditsAndNotifies() throws Exception {
    // seed 1001: emp 22, 5 days PTO 07/08-07/12, approver 21; 9007 pending 5
    mvc.perform(post("/api/leave/requests/1001/approve").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/leave/requests/424242/approve").header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_NOT_FOUND"));

    mvc.perform(
            json(post("/api/leave/requests/1001/approve"), manager, Map.of("comments", "Enjoy")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.approverEmpId").value(21))
        .andExpect(jsonPath("$.approverName").value("JENNIFER PARK"))
        .andExpect(jsonPath("$.approvalComments").value("Enjoy"))
        .andExpect(jsonPath("$.approvalDate").isNotEmpty());
    assertThat(balance(9007, "pending")).isEqualByComparingTo("0");
    assertThat(balance(9007, "used")).isEqualByComparingTo("7");
    assertThat(auditCount("LEAVE_REQUESTS", 1001, "STATUS_CHANGE")).isEqualTo(1);
    assertThat(notificationBody(22, "Leave Request Approved"))
        .isEqualTo("Your leave request from 07/08/2024 to 07/12/2024 has been approved.");

    // -20204 approve / reject non-pending
    mvc.perform(
            post("/api/leave/requests/1001/approve").header("Authorization", "Bearer " + manager))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20204"))
        .andExpect(jsonPath("$.message").value("Cannot approve request in status: APPROVED"));
    mvc.perform(json(post("/api/leave/requests/1001/reject"), manager, Map.of("comments", "late")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20204"))
        .andExpect(jsonPath("$.message").value("Cannot reject request in status: APPROVED"));

    // pending approvals of the manager: only 1003 left
    mvc.perform(get("/api/leave/approvals/pending").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].requestId").value(1003))
        .andExpect(jsonPath("$[0].empNumber").value("EMP-000023"))
        .andExpect(jsonPath("$[0].halfDay").value(true))
        .andExpect(jsonPath("$[0].halfDayPeriod").value("PM"));
    // team calendar now shows the approved 1001
    mvc.perform(
            get("/api/leave/team-calendar")
                .param("from", "2024-07-01")
                .param("to", "2024-07-31")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].requestId").value(1001))
        .andExpect(jsonPath("$[0].empName").value("THOMAS BAKER"))
        .andExpect(jsonPath("$[0].status").value("APPROVED"));
    mvc.perform(
            get("/api/leave/team-calendar")
                .param("from", "2024-07-31")
                .param("to", "2024-07-01")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20210"));
  }

  @Test
  void rejectRequiresCommentsReleasesPendingAndSelfDecisionIsForbidden() throws Exception {
    long id =
        body(mvc.perform(
                    json(post("/api/leave/requests"), staff, body(1, "2024-07-08", "2024-07-09")))
                .andReturn())
            .get("requestId")
            .asLong();
    // owner may neither approve nor reject their own request, even with authority
    mvc.perform(
            post("/api/leave/requests/" + id + "/approve")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    // manager 21 is neither approver of emp 2 nor LEAVE:APPROVE
    mvc.perform(
            json(post("/api/leave/requests/" + id + "/reject"), manager, Map.of("comments", "no")))
        .andExpect(status().isForbidden());
    // exec (LEAVE:APPROVE and designated approver): comments required
    mvc.perform(
            post("/api/leave/requests/" + id + "/reject").header("Authorization", "Bearer " + exec))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("comments"));
    mvc.perform(json(post("/api/leave/requests/" + id + "/reject"), exec, Map.of("comments", " ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("comments"));

    mvc.perform(
            json(
                post("/api/leave/requests/" + id + "/reject"),
                exec,
                Map.of("comments", "Release week")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.approverEmpId").value(1))
        .andExpect(jsonPath("$.approvalComments").value("Release week"));
    assertThat(balance(9001, "pending")).isEqualByComparingTo("0");
    assertThat(balance(9001, "used")).isEqualByComparingTo("3");
    assertThat(auditCount("LEAVE_REQUESTS", id, "STATUS_CHANGE")).isEqualTo(1);
    assertThat(notificationBody(2, "Leave Request Rejected"))
        .isEqualTo(
            "Your leave request from 07/08/2024 to 07/09/2024 has been rejected. Reason: Release"
                + " week");
    // cancel a REJECTED one -> -20204
    mvc.perform(
            post("/api/leave/requests/" + id + "/cancel")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20204"))
        .andExpect(jsonPath("$.message").value("Cannot cancel request in status: REJECTED"));
  }

  // ------------------------------------------------------------------ balances / business days

  @Test
  void balancesMineAndBusinessDaysWithObservedHolidays() throws Exception {
    mvc.perform(get("/api/leave/balances/mine").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].leaveTypeCode").value("PTO"))
        .andExpect(jsonPath("$[0].calendarYear").value(2024))
        .andExpect(jsonPath("$[0].available").value(9.5))
        .andExpect(jsonPath("$[0].carryoverFromPrev").value(5))
        .andExpect(jsonPath("$[1].leaveTypeCode").value("SICK"));
    mvc.perform(
            get("/api/leave/balances/mine")
                .param("year", "2023")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].available").value(5));
    mvc.perform(
            get("/api/leave/balances/mine")
                .param("year", "1999")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("year"));

    mvc.perform(
            get("/api/leave/business-days")
                .param("start", "2024-07-01")
                .param("end", "2024-07-07")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.start").value("2024-07-01"))
        .andExpect(jsonPath("$.end").value("2024-07-07"))
        .andExpect(jsonPath("$.businessDays").value(4))
        .andExpect(jsonPath("$.holidays.length()").value(1))
        .andExpect(jsonPath("$.holidays[0].holidayName").value("Independence Day"))
        .andExpect(jsonPath("$.holidays[0].holidayDate").value("2024-07-04"))
        .andExpect(jsonPath("$.holidays[0].observedDate").value("2024-07-04"));
    // BUG-05: a Saturday holiday is observed on the Friday
    jdbc.update(
        "insert into holidays (holiday_id, holiday_name, holiday_date, active_flag, created_by)"
            + " values (902, 'Sat Holiday', date '2024-07-13', 'Y', 't')");
    mvc.perform(
            get("/api/leave/business-days")
                .param("start", "2024-07-08")
                .param("end", "2024-07-12")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessDays").value(4))
        .andExpect(jsonPath("$.holidays[0].holidayDate").value("2024-07-13"))
        .andExpect(jsonPath("$.holidays[0].observedDate").value("2024-07-12"));
    jdbc.update("delete from holidays where holiday_id = 902");
    mvc.perform(
            get("/api/leave/business-days")
                .param("start", "2024-07-08")
                .param("end", "2024-07-07")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20210"))
        .andExpect(jsonPath("$.field").value("end"));
    mvc.perform(get("/api/leave/business-days").param("start", "2024-07-08"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void deferredAdminRoutesAreNotMounted() throws Exception {
    for (String p :
        new String[] {"accrual/run", "carryover/run", "carryover/expire", "balances/adjust"}) {
      mvc.perform(json(post("/api/leave/admin/" + p), exec, Map.of()))
          .andExpect(status().isNotFound());
    }
  }

  // ------------------------------------------------------------------ helpers

  private static Map<String, Object> body(int leaveTypeId, String start, String end) {
    Map<String, Object> m = new HashMap<>();
    m.put("leaveTypeId", leaveTypeId);
    m.put("startDate", start);
    m.put("endDate", end);
    m.put("halfDay", false);
    m.put("reason", "Trip");
    return m;
  }

  private BigDecimal balance(long balanceId, String column) {
    return jdbc.queryForObject(
        "select " + column + " from leave_balances where balance_id = ?",
        BigDecimal.class,
        balanceId);
  }

  private String token(String email) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
            .andReturn();
    return json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
    return b.header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }

  private int auditCount(String table, long recordId, String action) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from audit_log where table_name = ? and record_id = ? and action_type = ?",
            Integer.class,
            table,
            recordId,
            action);
    return n == null ? 0 : n;
  }

  private int notificationCount(long recipientEmpId, String subject) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from notification_queue where recipient_emp_id = ? and subject = ?",
            Integer.class,
            recipientEmpId,
            subject);
    return n == null ? 0 : n;
  }

  private String notificationBody(long recipientEmpId, String subject) {
    return jdbc.queryForObject(
        "select body from notification_queue where recipient_emp_id = ? and subject = ?"
            + " order by notification_id desc limit 1",
        String.class,
        recipientEmpId,
        subject);
  }
}
