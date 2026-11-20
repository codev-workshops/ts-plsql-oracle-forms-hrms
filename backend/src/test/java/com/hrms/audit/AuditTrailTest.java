package com.hrms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.auth.ApiTestSupport;
import com.hrms.auth.PasswordResetService;
import com.hrms.common.AuditRetentionService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P8 audit-trail verification (D-04, D-08): every audited write produces exactly the expected
 * {@code audit_log} rows, with the principal (or {@code system:<job>}) as actor and the injected clock as time,
 * and a failed audit write rolls the business change back.
 */
class AuditTrailTest extends ApiTestSupport {

    static final Instant NOW = Instant.parse("2026-03-02T09:00:00Z");
    static final String ADMIN = "james.richardson@company.com";

    @Autowired
    PasswordResetService resets;

    @Autowired
    AuditRetentionService retention;

    String admin;

    @BeforeEach
    void signIn() throws Exception {
        clock.set(NOW);
        admin = tokenFor(1);
    }

    @Test
    void authWritesSessionAndCredentialAudits() throws Exception {
        long m = mark();
        String token = tokenFor(36);
        audited(m, "priya.sharma@company.com");
        assertThat(rows(m)).containsExactly("USER_SESSIONS:INSERT");

        m = mark();
        send(token, post("/api/v1/auth/change-password"), Map.of("currentPassword", PASSWORD,
                "newPassword", "Changed#2026")).andExpect(status().isNoContent());
        assertThat(rows(m)).containsExactly("USER_CREDENTIALS:UPDATE");
        assertThat(jdbc.queryForObject("SELECT new_values::text FROM hrms.audit_log WHERE audit_id > ?", String.class,
                m)).doesNotContain("Changed#2026").doesNotContain("bcrypt");

        m = mark();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        assertThat(rows(m)).containsExactly("USER_SESSIONS:UPDATE");
        audited(m, "priya.sharma@company.com");

        m = mark();
        send(admin, post("/api/v1/auth/password-resets"), Map.of("empId", 36)).andExpect(status().isAccepted());
        assertThat(rows(m)).containsExactly("PASSWORD_RESET_TOKENS:INSERT");
        audited(m, ADMIN);
        m = mark();
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", resetToken(36), "newPassword", "Reset#2026"))))
                .andExpect(status().isNoContent());
        assertThat(rows(m)).containsExactly("USER_CREDENTIALS:UPDATE");
        audited(m, "priya.sharma@company.com");

        jdbc.update("DELETE FROM hrms.user_credentials WHERE emp_id = 41");
        m = mark();
        resets.issueInitialResets("system:cutover");
        assertThat(rows(m)).containsExactly("USER_CREDENTIALS:INSERT", "PASSWORD_RESET_TOKENS:INSERT");
        audited(m, "system:cutover");
    }

    @Test
    void employeeLifecycleAndSalaryRecordsAreAudited() throws Exception {
        long m = mark();
        long id = read(send(admin, post("/api/v1/employees"), Map.of("firstName", "Audit", "lastName", "Trail",
                "hireDate", "2026-03-16", "deptId", 31, "jobId", 60, "baseSalary", 50000))
                .andExpect(status().isCreated())).get("employee").get("empId").asLong();
        assertThat(rows(m)).containsExactlyInAnyOrder("EMPLOYEES:INSERT", "SALARY_RECORDS:INSERT");
        audited(m, ADMIN);

        m = mark();
        send(admin, patch("/api/v1/employees/" + id), Map.of("city", "Chicago")).andExpect(status().isOk());
        assertThat(rows(m)).containsExactly("EMPLOYEES:UPDATE");

        m = mark();
        send(admin, post("/api/v1/employees/34/transfer"), Map.of("newDeptId", 32)).andExpect(status().isOk());
        assertThat(rows(m)).containsExactly("EMPLOYEES:UPDATE");

        m = mark();
        send(admin, post("/api/v1/employees/33/promote"), Map.of("newJobId", 30, "newSalary", 120000,
                "effectiveDate", "2026-04-01")).andExpect(status().isOk());
        assertThat(rows(m)).containsExactlyInAnyOrder("EMPLOYEES:UPDATE", "SALARY_RECORDS:UPDATE",
                "SALARY_RECORDS:INSERT");
        assertThat(jdbc.queryForObject("SELECT new_values->>'salary' FROM hrms.audit_log WHERE audit_id > ? AND "
                + "table_name = 'SALARY_RECORDS' AND action_type = 'INSERT'", String.class, m)).isEqualTo("120000");

        m = mark();
        send(admin, post("/api/v1/employees/35/terminate"), Map.of("terminationDate", "2026-03-31",
                "reason", "VOLUNTARY")).andExpect(status().isOk());
        assertThat(rows(m)).containsExactlyInAnyOrder("EMPLOYEES:STATUS_CHANGE", "SALARY_RECORDS:UPDATE");

        m = mark();
        send(admin, post("/api/v1/employees/99/rehire"), Map.of("rehireDate", "2026-03-16", "deptId", 20,
                "jobId", 52, "baseSalary", 75000)).andExpect(status().isOk());
        assertThat(rows(m)).containsExactlyInAnyOrder("EMPLOYEES:STATUS_CHANGE", "SALARY_RECORDS:INSERT");
        assertThat(jdbc.queryForObject("SELECT old_values->>'employmentStatus' || '>' || (new_values->>"
                + "'employmentStatus') FROM hrms.audit_log WHERE audit_id > ? AND action_type = 'STATUS_CHANGE'",
                String.class, m)).isEqualTo("TERMINATED>ACTIVE");
        audited(m, ADMIN);
    }

    @Test
    void leavePayrollAndPerformanceWritesAreAudited() throws Exception {
        jdbc.update("INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, opening_balance, "
                + "created_by) VALUES (37, 1, 2026, 10, 'test') ON CONFLICT DO NOTHING");
        String alex = tokenFor(37);
        String kevin = tokenFor(31);
        long m = mark();
        long request = read(send(alex, post("/api/v1/leave/requests"), Map.of("leaveTypeId", 1,
                "startDate", "2026-03-09", "endDate", "2026-03-10")).andExpect(status().isCreated()))
                .get("requestId").asLong();
        send(kevin, post("/api/v1/leave/requests/" + request + "/approve"), Map.of()).andExpect(status().isOk());
        assertThat(rows(m)).containsExactly("LEAVE_REQUESTS:INSERT", "LEAVE_REQUESTS:STATUS_CHANGE");
        assertThat(actors(m)).containsExactly("alex.taylor@company.com", "kevin.garcia@company.com");

        send(admin, post("/api/v1/payroll/pay-periods/generate"), Map.of("year", 2026, "frequency", "MONTHLY"))
                .andExpect(status().isCreated());
        long march = jdbc.queryForObject("SELECT period_id FROM hrms.pay_periods WHERE period_name = '2026-03 (Mar)'",
                Long.class);
        long april = jdbc.queryForObject("SELECT period_id FROM hrms.pay_periods WHERE period_name = '2026-04 (Apr)'",
                Long.class);
        m = mark();
        long run = read(send(admin, post("/api/v1/payroll/runs"), Map.of("periodId", march))
                .andExpect(status().isCreated())).get("runId").asLong();
        send(admin, post("/api/v1/payroll/runs/" + run + "/calculate"), Map.of()).andExpect(status().isOk());
        send(admin, post("/api/v1/payroll/runs/" + run + "/approve"), Map.of()).andExpect(status().isOk());
        send(admin, post("/api/v1/payroll/runs/" + run + "/reverse"), Map.of("reason", "audit check"))
                .andExpect(status().isOk());
        send(admin, post("/api/v1/payroll/pay-periods/" + april + "/close"), Map.of()).andExpect(status().isOk());
        assertThat(rows(m)).containsExactly("PAYROLL_RUNS:INSERT", "PAYROLL_RUNS:STATUS_CHANGE",
                "PAYROLL_RUNS:STATUS_CHANGE", "PAYROLL_RUNS:STATUS_CHANGE", "PAY_PERIODS:STATUS_CHANGE");
        audited(m, ADMIN);

        m = mark();
        long cycle = read(send(admin, post("/api/v1/performance/cycles"), Map.of("cycleName", "Audit", "cycleYear",
                2026, "startDate", "2026-01-01", "endDate", "2026-12-31")).andExpect(status().isCreated()))
                .get("cycleId").asLong();
        send(admin, post("/api/v1/performance/cycles/" + cycle + "/open"), Map.of()).andExpect(status().isOk());
        assertThat(rows(m)).containsExactly("REVIEW_CYCLES:INSERT", "REVIEW_CYCLES:STATUS_CHANGE");
        m = mark();
        int generated = read(send(admin, post("/api/v1/performance/cycles/" + cycle + "/reviews/generate"), Map.of())
                .andExpect(status().isOk())).get("reviewsCreated").asInt();
        assertThat(generated).isPositive();
        assertThat(rows(m)).hasSize(generated).containsOnly("PERFORMANCE_REVIEWS:INSERT");
        long review = jdbc.queryForObject("SELECT review_id FROM hrms.performance_reviews WHERE cycle_id = ? AND "
                + "emp_id = 37", Long.class, cycle);
        m = mark();
        send(alex, post("/api/v1/performance/reviews/" + review + "/self-assessment"), Map.of("selfAssessment", "ok"))
                .andExpect(status().isOk());
        send(alex, post("/api/v1/performance/reviews/" + review + "/goals"), Map.of("goalTitle", "Audit",
                "weightPct", 10)).andExpect(status().isCreated());
        assertThat(rows(m)).containsExactly("PERFORMANCE_REVIEWS:STATUS_CHANGE", "PERFORMANCE_GOALS:INSERT");
        audited(m, "alex.taylor@company.com");
    }

    @Test
    void aFailedAuditWriteRollsTheBusinessChangeBack() throws Exception { // D-08
        jdbc.execute("ALTER TABLE hrms.audit_log ADD CONSTRAINT chk_test_reject CHECK (table_name <> 'EMPLOYEES') "
                + "NOT VALID");
        try {
            send(admin, patch("/api/v1/employees/24"), Map.of("city", "Nowhere"))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("ALTER TABLE hrms.audit_log DROP CONSTRAINT chk_test_reject");
        }
        assertThat(jdbc.queryForObject("SELECT city FROM hrms.employees WHERE emp_id = 24", String.class))
                .isNotEqualTo("Nowhere");
    }

    @Test
    void retentionPurgeDeletesOnlyOldRowsAndAuditsItself() { // PKG-A02, A-P8-02
        jdbc.update("INSERT INTO hrms.audit_log (table_name, record_id, action_type, changed_by, changed_date) "
                + "VALUES ('OLD', 1, 'UPDATE', 'legacy', TIMESTAMPTZ '2025-03-01 00:00:00Z'), "
                + "('KEEP', 1, 'UPDATE', 'legacy', TIMESTAMPTZ '2025-03-03 00:00:00Z')");
        long m = mark();
        AuditRetentionService.PurgeResult r = retention.purge();
        assertThat(r.purgedBefore()).isEqualTo(OffsetDateTime.of(2025, 3, 2, 9, 0, 0, 0, ZoneOffset.UTC));
        assertThat(r.deleted()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.audit_log WHERE table_name IN ('OLD', 'KEEP')",
                Integer.class)).isEqualTo(1);
        assertThat(rows(m)).containsExactly("AUDIT_LOG:DELETE");
        audited(m, AuditRetentionService.ACTOR);
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('hrms_app', 'hrms.audit_log', 'DELETE')",
                Boolean.class)).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private long mark() {
        return jdbc.queryForObject("SELECT COALESCE(max(audit_id), 0) FROM hrms.audit_log", Long.class);
    }

    private List<String> rows(long mark) {
        return jdbc.queryForList("SELECT table_name || ':' || action_type FROM hrms.audit_log WHERE audit_id > ? "
                + "ORDER BY audit_id", String.class, mark);
    }

    private List<String> actors(long mark) {
        return jdbc.queryForList("SELECT changed_by FROM hrms.audit_log WHERE audit_id > ? ORDER BY audit_id",
                String.class, mark);
    }

    /** Every row since the mark: the expected actor and the injected clock (D-04). */
    private void audited(long mark, String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT changed_by, changed_date FROM hrms.audit_log "
                + "WHERE audit_id > ?", mark);
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            assertThat(r.get("changed_by")).isEqualTo(actor);
            assertThat(((java.sql.Timestamp) r.get("changed_date")).toInstant()).isEqualTo(NOW);
        });
    }

    private String resetToken(long empId) {
        String body = jdbc.queryForObject("SELECT body FROM hrms.notification_queue WHERE recipient_emp_id = ? "
                + "AND subject = 'HRMS password setup' ORDER BY notification_id DESC LIMIT 1", String.class, empId);
        Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder request, Map<String, Object> body)
            throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new HashMap<>(body))));
    }
}
