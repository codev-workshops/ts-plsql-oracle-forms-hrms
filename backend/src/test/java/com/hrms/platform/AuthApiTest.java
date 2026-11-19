package com.hrms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthApiTest extends IntegrationTest {

    @Test
    void loginReturnsTokenAndProfile() throws Exception {
        String token = login("Thomas.Baker@company.com");
        call(get("/api/v1/auth/me"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empId").value(22))
                .andExpect(jsonPath("$.managerEmpId").value(21))
                .andExpect(jsonPath("$.username").value("thomas.baker@company.com"));
    }

    @Test
    void wrongPasswordIsRejectedWithLegacyCode() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "thomas.baker@company.com",
                                "password", "nope"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("-20301"));
    }

    @Test
    void accountLocksAfterMaxFailedAttempts() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"emily.johnson@company.com\",\"password\":\"bad\"}"));
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "emily.johnson@company.com",
                                "password", DEV_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("-20302"));
    }

    @Test
    void terminatedEmployeeCannotLogIn() throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE employment_status = 'TERMINATED'"
                + " LIMIT 1", String.class);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", email, "password", DEV_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("-20301"));
    }

    @Test
    void protectedRoutesRequireToken() throws Exception {
        mvc.perform(get("/api/v1/leave/types"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("-20303"));
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        String token = login("thomas.baker@company.com");
        call(post("/api/v1/auth/logout"), token).andExpect(status().isNoContent());
        call(get("/api/v1/auth/me"), token).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeEnforcesPolicy() throws Exception {
        String token = login("thomas.baker@company.com");
        callJson(post("/api/v1/auth/password"), token, Map.of("currentPassword", DEV_PASSWORD,
                "newPassword", "short")).andExpect(jsonPath("$.code").value("-20310"));
        callJson(post("/api/v1/auth/password"), token, Map.of("currentPassword", DEV_PASSWORD,
                "newPassword", "alllowercase1")).andExpect(jsonPath("$.code").value("-20311"));
        callJson(post("/api/v1/auth/password"), token, Map.of("currentPassword", DEV_PASSWORD,
                "newPassword", "NoDigitsHere")).andExpect(jsonPath("$.code").value("-20312"));
        callJson(post("/api/v1/auth/password"), token, Map.of("currentPassword", DEV_PASSWORD,
                "newPassword", "Better#Pass1")).andExpect(status().isNoContent());
        Integer audits = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE table_name = 'USER_CREDENTIALS'",
                Integer.class);
        assertThat(audits).isEqualTo(1);
    }
}
