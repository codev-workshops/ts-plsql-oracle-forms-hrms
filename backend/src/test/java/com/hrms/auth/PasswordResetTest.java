package com.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** OQ-15 production half (P8, contract v1.5): unusable credentials + one-time reset links. */
class PasswordResetTest extends ApiTestSupport {

    private static final Pattern LINK = Pattern.compile("reset-password#token=([A-Za-z0-9_-]+)");

    @Autowired
    PasswordResetService resets;

    @BeforeEach
    void noCredentialsForTheMigratedEmployees() {
        clock.set(Instant.parse("2026-03-02T09:00:00Z"));
        jdbc.update("DELETE FROM hrms.user_credentials WHERE emp_id IN (36, 37)");
    }

    @Test
    void cutoverGivesUnusableCredentialsAndOneTimeLinks() throws Exception {
        PasswordResetService.InitialResets first = resets.issueInitialResets("system:cutover");
        assertThat(first.issued()).isEqualTo(2);
        assertThat(resets.issueInitialResets("system:cutover").issued()).isZero(); // repeatable
        assertThat(jdbc.queryForList("SELECT emp_id FROM hrms.user_credentials WHERE reset_required ORDER BY emp_id",
                Long.class)).containsExactly(36L, 37L);
        login("alex.taylor@company.com", "Hrms#2026").andExpect(status().isUnauthorized());

        String token = tokenFromMail(37);
        expect(confirm(token, "short"), 400, "PASSWORD_POLICY").andExpect(jsonPath("$.legacyCode").value(-20310));
        expect(confirm("not-a-token", "Welcome#2026"), 422, "INVALID_RESET_TOKEN");
        confirm(token, "Welcome#2026").andExpect(status().isNoContent());
        login("alex.taylor@company.com", "Welcome#2026").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT reset_required FROM hrms.user_credentials WHERE emp_id = 37",
                Boolean.class)).isFalse();
        expect(confirm(token, "Another#2026"), 422, "INVALID_RESET_TOKEN"); // one-time
        assertThat(jdbc.queryForObject("SELECT body FROM hrms.notification_queue WHERE recipient_emp_id = 37 "
                + "AND subject = 'HRMS password setup'", String.class)).isEqualTo("[reset link used]");
    }

    @Test
    void hrIssuesLinksThatSupersedeExpireAndCloseSessions() throws Exception {
        String admin = tokenFor(1);
        String employee = tokenFor(22);
        expect(issue(employee, 36), 403, "FORBIDDEN");
        expect(issue(admin, 424242), 404, "EMPLOYEE_NOT_FOUND");
        expect(issue(admin, 99), 422, "EMPLOYEE_NOT_ACTIVE"); // TERMINATED
        expect(issue(admin, null), 400, "VALIDATION_FAILED");

        issue(admin, 36).andExpect(status().isAccepted()).andExpect(jsonPath("$.empId").value(36))
                .andExpect(jsonPath("$.expiresAt").value("2026-03-09T09:00:00Z"));
        String superseded = tokenFromMail(36);
        issue(admin, 36).andExpect(status().isAccepted());
        String current = tokenFromMail(36);
        expect(confirm(superseded, "Welcome#2026"), 422, "INVALID_RESET_TOKEN");
        clock.advance(Duration.ofDays(7));
        expect(confirm(current, "Welcome#2026"), 422, "INVALID_RESET_TOKEN"); // expired
        clock.set(Instant.parse("2026-03-02T09:00:00Z"));

        // A reset for someone already signed in closes every session (the link may have been intercepted)
        issue(admin, 22).andExpect(status().isAccepted());
        confirm(tokenFromMail(22), "Fresh#Pass9").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(employee)))
                .andExpect(status().isUnauthorized());
        login("thomas.baker@company.com", PASSWORD).andExpect(status().isUnauthorized());
    }

    private String tokenFromMail(long empId) {
        String body = jdbc.queryForObject("SELECT body FROM hrms.notification_queue WHERE recipient_emp_id = ? "
                + "AND subject = 'HRMS password setup' ORDER BY notification_id DESC LIMIT 1", String.class, empId);
        Matcher m = LINK.matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return m.group(1);
    }

    private ResultActions confirm(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token, "newPassword", password))));
    }

    private ResultActions issue(String bearerToken, Integer empId) throws Exception {
        return mvc.perform(post("/api/v1/auth/password-resets").header("Authorization", bearer(bearerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(empId == null ? "{}" : json.writeValueAsString(Map.of("empId", empId))));
    }

    private static ResultActions expect(ResultActions r, int status, String code) throws Exception {
        return r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
}
