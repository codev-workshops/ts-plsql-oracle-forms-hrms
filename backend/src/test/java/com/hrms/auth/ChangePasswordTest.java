package com.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** PKG_SECURITY.change_password, implemented for real (PKG-S02, FRM-015, MNU-005). */
class ChangePasswordTest extends ApiTestSupport {

    @Test
    void policyIsCheckedInLegacyOrderWithLegacyCodes() throws Exception {
        String token = tokenFor(20);
        change(token, PASSWORD, "Ab1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY")).andExpect(jsonPath("$.legacyCode").value(-20310));
        change(token, PASSWORD, "lowercase1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.legacyCode").value(-20311));
        change(token, PASSWORD, "NoDigitsHere").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.legacyCode").value(-20312));
        change(token, PASSWORD, "A1" + "x".repeat(80)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));
    }

    @Test
    void wrongCurrentPasswordIsRejected() throws Exception {
        String token = tokenFor(21);
        change(token, "Wrong#Pass1", "NewPass#2026").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        login("jennifer.park@company.com", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void changeRehashesClosesOtherSessionsAndAudits() throws Exception {
        String current = tokenFor(22);
        String other = login("thomas.baker@company.com", PASSWORD).andReturn().getResponse().getContentAsString();
        String otherToken = json.readTree(other).get("accessToken").asText();

        change(current, PASSWORD, "NewPass#2026").andExpect(status().isNoContent());

        String hash = jdbc.queryForObject("SELECT password_hash FROM hrms.user_credentials WHERE emp_id = 22",
                String.class);
        assertThat(hash).startsWith("{bcrypt}");
        login("thomas.baker@company.com", PASSWORD).andExpect(status().isUnauthorized());
        login("thomas.baker@company.com", "NewPass#2026").andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(current))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hrms.audit_log WHERE table_name = 'USER_CREDENTIALS' AND record_id = 22
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT new_values::text FROM hrms.audit_log WHERE table_name = 'USER_CREDENTIALS' AND record_id = 22
                """, String.class)).doesNotContain("NewPass").doesNotContain("bcrypt");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"Abcdefg1\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions change(String token, String current, String next) throws Exception {
        return mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
    }
}
