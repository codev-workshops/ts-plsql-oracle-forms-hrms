package com.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AuthApiTest extends ApiTestSupport {

    private static final String PROBLEM = "application/problem+json";

    @Test
    void loginIssuesOpaqueTokenAndStoresOnlyItsHash() throws Exception {
        provision(1);
        JsonNode body = read(login("  James.Richardson@COMPANY.com ", PASSWORD).andExpect(status().isOk()));
        String token = body.get("accessToken").asText();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(body.at("/user/empId").asLong()).isEqualTo(1);
        assertThat(body.at("/user/username").asText()).isEqualTo("james.richardson@company.com");

        Map<String, Object> session = jdbc.queryForMap(
                "SELECT * FROM hrms.user_sessions WHERE token_hash = ?", Tokens.hash(token));
        assertThat(session.get("session_status")).isEqualTo("ACTIVE");
        assertThat(session.get("emp_id")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.user_sessions WHERE token_hash = ?",
                Integer.class, token)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT extract(epoch FROM expires_at - login_time)::int FROM hrms.user_sessions WHERE token_hash = ?
                """, Integer.class, Tokens.hash(token))).isEqualTo(30 * 60);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hrms.audit_log WHERE table_name = 'USER_SESSIONS' AND record_id = ?
                """, Integer.class, session.get("session_id"))).isEqualTo(1);
    }

    @Test
    void unknownUserBadPasswordAndInactiveEmployeeAreIndistinguishable() throws Exception {
        provision(2);
        provision(99);
        String unknown = login("nobody@company.com", PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.legacyCode").value(-20301))
                .andReturn().getResponse().getContentAsString();
        String badPassword = login("sarah.chen@company.com", "Wrong#Pass1").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String terminated = login("brian.foster@company.com", PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutTrace(badPassword)).isEqualTo(withoutTrace(unknown));
        assertThat(withoutTrace(terminated)).isEqualTo(withoutTrace(unknown));
    }

    @Test
    void employeeWithoutCredentialCannotLogIn() throws Exception {
        jdbc.update("DELETE FROM hrms.user_credentials WHERE emp_id = 3");
        login("michael.oconnor@company.com", "Anything#1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void duplicateActiveEmailResolvesToLowestEmpId() throws Exception {
        // The active-email unique index only covers active_flag rows; a soft-deleted row can still be ACTIVE.
        jdbc.update("""
                INSERT INTO hrms.employees (emp_id, emp_number, first_name, last_name, email, hire_date, dept_id,
                    job_id, location_code, employment_type, employment_status, active_flag, created_by)
                VALUES (5, 'EMP-000005', 'DUP', 'LICATE', 'KEVIN.GARCIA@company.com', DATE '2020-01-01', 31, 60,
                    'CHI', 'FULL_TIME', 'ACTIVE', false, 'test')
                """);
        provision(5);
        provision(31);
        login("kevin.garcia@company.com", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.empId").value(5));
    }

    @Test
    void fiveConsecutiveFailuresLockForFifteenMinutes() throws Exception {
        provision(33);
        String user = "daniel.lee@company.com";
        for (int i = 0; i < 4; i++) {
            login(user, "Wrong#Pass1").andExpect(status().isUnauthorized());
        }
        assertThat(failedAttempts(33)).isEqualTo(4);
        login(user, "Wrong#Pass1").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM hrms.user_credentials WHERE emp_id = 33",
                Boolean.class)).isTrue();

        login(user, PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        clock.advance(Duration.ofMinutes(14));
        login(user, PASSWORD).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofMinutes(1));
        login(user, PASSWORD).andExpect(status().isOk());
        assertThat(failedAttempts(33)).isZero();
    }

    @Test
    void successfulLoginResetsFailureCounter() throws Exception {
        provision(34);
        String user = "jessica.nguyen@company.com";
        for (int i = 0; i < 4; i++) {
            login(user, "Wrong#Pass1").andExpect(status().isUnauthorized());
        }
        login(user, PASSWORD).andExpect(status().isOk());
        assertThat(failedAttempts(34)).isZero();
        for (int i = 0; i < 4; i++) {
            login(user, "Wrong#Pass1").andExpect(status().isUnauthorized());
        }
        login(user, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void protectedRoutesRequireValidBearerToken() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, bearer("not-a-token")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/menu").header(HttpHeaders.AUTHORIZATION, "Basic Zm9vOmJhcg=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionExpiresThirtyMinutesAfterLoginRegardlessOfActivity() throws Exception {
        String token = tokenFor(35);
        clock.advance(Duration.ofMinutes(29));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.empId").value(35));
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized());
        assertThat(sessionStatus(token)).isEqualTo("EXPIRED");
    }

    @Test
    void logoutRevokesTheSession() throws Exception {
        String token = tokenFor(36);
        mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        assertThat(sessionStatus(token)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT logout_time IS NOT NULL FROM hrms.user_sessions WHERE token_hash = ?",
                Boolean.class, Tokens.hash(token))).isTrue();
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionStopsWorkingOnceEmployeeIsNoLongerActive() throws Exception {
        String token = tokenFor(43);
        jdbc.update("UPDATE hrms.employees SET employment_status = 'SUSPENDED' WHERE emp_id = 43");
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginValidatesRequiredFields() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void passwordsLongerThanBcryptLimitNeverAuthenticate() throws Exception {
        provision(37);
        login("alex.taylor@company.com", PASSWORD + "x".repeat(80)).andExpect(status().isUnauthorized());
    }

    private int failedAttempts(long empId) {
        return jdbc.queryForObject("SELECT failed_attempts FROM hrms.user_credentials WHERE emp_id = ?",
                Integer.class, empId);
    }

    private String sessionStatus(String token) {
        return jdbc.queryForObject("SELECT session_status FROM hrms.user_sessions WHERE token_hash = ?",
                String.class, Tokens.hash(token));
    }

    private static String withoutTrace(String problem) {
        return problem.replaceAll("\"traceId\":\"[^\"]*\"", "");
    }
}
