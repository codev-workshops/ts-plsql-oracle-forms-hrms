package com.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.auth.HrmsPrincipal;
import com.hrms.common.ApiException;
import com.hrms.common.ErrorCode;
import com.hrms.notification.NotificationDispatcher;
import com.hrms.notification.NotificationSender;
import com.hrms.notification.NotificationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

@Import(LeaveAccrualAndOutboxTest.FailingSender.class)
class LeaveAccrualAndOutboxTest extends LeaveTestSupport {

    @TestConfiguration
    static class FailingSender {
        @Bean
        @Primary
        NotificationSender failOnSubject() {
            return n -> {
                if (n.subject().contains("FAIL")) {
                    throw new IllegalStateException("smtp down");
                }
            };
        }
    }

    @Autowired
    LeaveService leave;

    @Autowired
    NotificationService notifications;

    @Autowired
    NotificationDispatcher dispatcher;

    private org.springframework.test.web.servlet.ResultActions accrue(String token, String date) throws Exception {
        return as(token, post("/api/v1/leave/accrual-runs").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("accrualDate", date))));
    }

    @Test
    void monthlyAccrualHonoursCapTenureAndIsIdempotentPerMonth() throws Exception {
        String admin = tokenFor(1);
        accrue(tokenFor(37), "2026-03-01").andExpect(status().isForbidden());
        jdbc.update("UPDATE hrms.leave_balances SET opening_balance = 19.5 WHERE emp_id = 37 AND leave_type_id = 1");
        jdbc.update("UPDATE hrms.leave_balances SET opening_balance = 20 WHERE emp_id = 36 AND leave_type_id = 1");
        jdbc.update("DELETE FROM hrms.leave_balances WHERE emp_id = 23");
        jdbc.update("UPDATE hrms.leave_types SET min_tenure_days = 30 WHERE leave_type_id = 2");
        jdbc.update("UPDATE hrms.employees SET hire_date = DATE '2026-02-15' WHERE emp_id = 24");
        try {
            long runId = read(accrue(admin, "2026-03-01").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accrualPeriod").value("2026-03-01"))
                    .andExpect(jsonPath("$.employeesProcessed").value(23))).get("runId").asLong();
            assertThat(balance(12, PTO, "accrued")).isEqualTo(1.25);
            assertThat(balance(12, SICK, "accrued")).isEqualTo(0.83);
            assertThat(balance(37, PTO, "accrued")).isEqualTo(0.5); // capped at MAX_BALANCE 20
            assertThat(balance(36, PTO, "accrued")).isZero();
            assertThat(balance(24, PTO, "accrued")).isEqualTo(1.25);
            assertThat(balance(24, SICK, "accrued")).isZero(); // 14 days < 30-day tenure
            assertThat(balance(23, PTO, "accrued")).isEqualTo(1.25); // balances initialized
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.leave_balances WHERE emp_id = 23",
                    Integer.class)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT balance_after FROM hrms.leave_accrual_log WHERE emp_id = 37 "
                    + "AND leave_type_id = 1", Double.class)).isEqualTo(20.0);
            int entries = jdbc.queryForObject("SELECT count(*) FROM hrms.leave_accrual_log", Integer.class);

            accrue(admin, "2026-03-15").andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(runId))
                    .andExpect(jsonPath("$.entriesCreated").value(entries));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.leave_accrual_log", Integer.class))
                    .isEqualTo(entries);
            assertThat(balance(12, PTO, "accrued")).isEqualTo(1.25);
            accrue(admin, "2026-04-01").andExpect(status().isCreated());
            assertThat(balance(12, PTO, "accrued")).isEqualTo(2.5);
        } finally {
            jdbc.update("UPDATE hrms.leave_types SET min_tenure_days = 0 WHERE leave_type_id = 2");
            jdbc.update("UPDATE hrms.employees SET hire_date = DATE '2022-06-15' WHERE emp_id = 24");
        }
    }

    @Test
    void dispatcherMarksSentAndFailedAndRequeuesBelowTheRetryLimit() {
        long ok = notifications.queueEmail(12, "hello", "body", null, null, "test");
        long bad = notifications.queueEmail(12, "FAIL", "body", null, null, "test");
        assertThat(jdbc.queryForObject("SELECT recipient_email FROM hrms.notification_queue WHERE notification_id = ?",
                String.class, ok)).isEqualTo("emily.johnson@company.com");
        assertThat(dispatcher.dispatchPending(50)).isEqualTo(new NotificationDispatcher.DispatchResult(1, 1));
        assertThat(state(ok)).isEqualTo("SENT:0");
        assertThat(state(bad)).isEqualTo("FAILED:1");
        assertThat(jdbc.queryForObject("SELECT error_message FROM hrms.notification_queue WHERE notification_id = ?",
                String.class, bad)).isEqualTo("smtp down");
        assertThat(dispatcher.requeueFailed(3)).isEqualTo(1);
        dispatcher.dispatchPending(50);
        dispatcher.requeueFailed(3);
        dispatcher.dispatchPending(50);
        assertThat(state(bad)).isEqualTo("FAILED:3");
        assertThat(dispatcher.requeueFailed(3)).isZero();
    }

    @Test
    void terminationEntryPointCancelsPendingAndReleasesTheBalance() { // DEF-22, used by P5
        HrmsPrincipal alex = principal(37);
        leave.submit(new LeaveService.Caller(alex, null), new LeaveModels.SubmitLeaveRequest(PTO,
                LocalDate.parse("2026-03-09"), LocalDate.parse("2026-03-10"), null, null, null));
        assertThat(balance(37, PTO, "pending")).isEqualTo(2.0);
        assertThat(leave.cancelPendingRequestsOf(37, "terminated", "system:p5")).isEqualTo(1);
        assertThat(balance(37, PTO, "pending")).isZero();
        assertThat(jdbc.queryForObject("SELECT changed_by FROM hrms.audit_log WHERE action_type = 'STATUS_CHANGE'",
                String.class)).isEqualTo("system:p5");
    }

    @Test
    void inactiveEmployeesCannotSubmit() {
        jdbc.update("UPDATE hrms.employees SET employment_status = 'SUSPENDED' WHERE emp_id = 36");
        try {
            assertThatThrownBy(() -> leave.submit(new LeaveService.Caller(principal(36), null),
                    new LeaveModels.SubmitLeaveRequest(PTO, LocalDate.parse("2026-03-09"),
                            LocalDate.parse("2026-03-09"), null, null, null)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.EMPLOYEE_NOT_ACTIVE));
        } finally {
            jdbc.update("UPDATE hrms.employees SET employment_status = 'ACTIVE' WHERE emp_id = 36");
        }
        assertThat(ErrorCode.EMPLOYEE_NOT_ACTIVE.legacyCode()).isEqualTo(-20001);
    }

    private static HrmsPrincipal principal(long empId) {
        return new HrmsPrincipal(empId, "emp" + empId, 1, OffsetDateTime.now(), OffsetDateTime.now(), Set.of());
    }

    private String state(long id) {
        return jdbc.queryForObject("SELECT status || ':' || retry_count FROM hrms.notification_queue "
                + "WHERE notification_id = ?", String.class, id);
    }
}
