package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Level 1 for the frozen /api/auth/* contract (openapi.yaml + error-codes.md). */
class AuthFlowTest extends AuthApiTestBase {

  @BeforeEach
  void seed() {
    seedAccounts();
  }

  private MvcResult login(String user, String password) throws Exception {
    return mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        java.util.Map.of("username", user, "password", password))))
        .andReturn();
  }

  @Test
  void loginReturnsJwtUserAndHttpOnlyRefreshCookie() throws Exception {
    MvcResult r = login(EXEC_EMAIL, PASSWORD);
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = json.readTree(r.getResponse().getContentAsString());
    assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
    assertThat(body.get("expiresIn").asInt()).isEqualTo(1800);
    assertThat(body.get("accessToken").asText()).contains(".");
    assertThat(body.get("user").get("empId").asLong()).isEqualTo(1);
    assertThat(body.get("user").get("userId").asText()).isEqualTo("1");
    assertThat(body.get("user").get("displayName").asText()).isEqualTo("JAMES RICHARDSON");
    assertThat(body.get("user").get("roles").toString()).contains("PAYROLL:APPROVE", "ADMIN:VIEW");
    assertThat(body.has("refreshToken")).isFalse();
    Cookie c = refreshCookie(r);
    assertThat(c.isHttpOnly()).isTrue();
    assertThat(c.getPath()).isEqualTo("/api/auth");
    String setCookie = r.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("SameSite=Strict");

    Integer audits =
        jdbc.queryForObject(
            "select count(*) from audit_log where action_type = 'LOGIN' and table_name = 'USER_SESSIONS'",
            Integer.class);
    assertThat(audits).isEqualTo(1);
    Integer sessions =
        jdbc.queryForObject(
            "select count(*) from user_sessions where emp_id = 1 and session_status = 'ACTIVE'"
                + " and jwt_jti is not null",
            Integer.class);
    assertThat(sessions).isEqualTo(1);
  }

  @Test
  void everyLoginFailureIsTheSame20301() throws Exception {
    for (String[] attempt :
        new String[][] {
          {"nobody@company.com", PASSWORD}, {EXEC_EMAIL, "wrong-Password1"},
        }) {
      MvcResult r = login(attempt[0], attempt[1]);
      assertThat(r.getResponse().getStatus()).isEqualTo(401);
      JsonNode e = json.readTree(r.getResponse().getContentAsString());
      assertThat(e.get("code").asText()).isEqualTo("-20301");
      assertThat(e.get("message").asText()).isEqualTo("Invalid username or password");
      assertThat(e.get("traceId").asText()).isNotBlank();
      assertThat(e.has("field")).isFalse();
    }
    jdbc.update("update employees set employment_status = 'TERMINATED' where emp_id = 2");
    try {
      MvcResult r = login(STAFF_EMAIL, PASSWORD);
      assertThat(r.getResponse().getStatus()).isEqualTo(401);
      assertThat(json.readTree(r.getResponse().getContentAsString()).get("code").asText())
          .isEqualTo("-20301");
    } finally {
      jdbc.update("update employees set employment_status = 'ACTIVE' where emp_id = 2");
    }
  }

  @Test
  void malformedLoginBodyIsValidationFailed() throws Exception {
    MvcResult r = login("not-an-email", PASSWORD);
    assertThat(r.getResponse().getStatus()).isEqualTo(400);
    JsonNode e = json.readTree(r.getResponse().getContentAsString());
    assertThat(e.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(e.get("field").asText()).isEqualTo("username");
  }

  @Test
  void fiveFailuresLockTheUsernameFor15Minutes() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertThat(login("locked@company.com", "x").getResponse().getStatus()).isEqualTo(401);
    }
    MvcResult r = login("locked@company.com", "x");
    assertThat(r.getResponse().getStatus()).isEqualTo(429);
    assertThat(r.getResponse().getHeader("Retry-After")).isNotBlank();
    assertThat(json.readTree(r.getResponse().getContentAsString()).get("code").asText())
        .isEqualTo("RATE_LIMITED");
    // other usernames are unaffected
    assertThat(login(EXEC_EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void meRequiresBearerAndReflectsJwtIdentity() throws Exception {
    mvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    String token = accessToken(login(STAFF_EMAIL, PASSWORD));
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.empId").value(2))
        .andExpect(jsonPath("$.email").value(STAFF_EMAIL))
        .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE:VIEW"))
        .andExpect(jsonPath("$.mustChangePassword").value(false));
  }

  @Test
  void refreshRotatesCookieAndRejectsReplay() throws Exception {
    MvcResult first = login(EXEC_EMAIL, PASSWORD);
    Cookie c1 = refreshCookie(first);

    MvcResult second = mvc.perform(post("/api/auth/refresh").cookie(c1)).andReturn();
    assertThat(second.getResponse().getStatus()).isEqualTo(200);
    Cookie c2 = refreshCookie(second);
    assertThat(c2.getValue()).isNotEqualTo(c1.getValue());
    assertThat(accessToken(second)).isNotEqualTo(accessToken(first));

    // replay of the rotated token -> 401 and the whole session is dead
    mvc.perform(post("/api/auth/refresh").cookie(c1))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    mvc.perform(post("/api/auth/refresh").cookie(c2)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken(second)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshWithoutCookieIsTokenInvalid() throws Exception {
    mvc.perform(post("/api/auth/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
  }

  @Test
  void logoutRevokesJtiClosesSessionAndIsIdempotent() throws Exception {
    MvcResult r = login(EXEC_EMAIL, PASSWORD);
    String token = accessToken(r);
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent())
        .andExpect(
            header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    mvc.perform(post("/api/auth/refresh").cookie(refreshCookie(r)))
        .andExpect(status().isUnauthorized());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_sessions where session_status = 'CLOSED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where action_type = 'LOGOUT'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void passwordPolicyCodesInFrozenOrder() throws Exception {
    String token = accessToken(login(EXEC_EMAIL, PASSWORD));
    expectPasswordError(
        token, PASSWORD, "short1A", "-20310", "Password must be at least 8 characters");
    expectPasswordError(
        token, PASSWORD, "alllowercase1", "-20311", "Password must contain an uppercase letter");
    expectPasswordError(
        token, PASSWORD, "NoDigitsHere", "-20312", "Password must contain a number");
    expectPasswordError(
        token,
        PASSWORD,
        PASSWORD,
        "PASSWORD_REUSED",
        "New password must differ from the current one");
    MvcResult wrongCurrent = changePassword(token, "Not-the-current1", "Brand-new1");
    assertThat(wrongCurrent.getResponse().getStatus()).isEqualTo(401);
    JsonNode e = json.readTree(wrongCurrent.getResponse().getContentAsString());
    assertThat(e.get("code").asText()).isEqualTo("-20301");
    assertThat(e.get("field").asText()).isEqualTo("currentPassword");
  }

  @Test
  void passwordChangeKeepsCurrentSessionAndKillsOthers() throws Exception {
    MvcResult s1 = login(EXEC_EMAIL, PASSWORD);
    MvcResult s2 = login(EXEC_EMAIL, PASSWORD);
    String t1 = accessToken(s1);
    assertThat(changePassword(t1, PASSWORD, "Brand-new1").getResponse().getStatus()).isEqualTo(204);

    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t1))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken(s2)))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/refresh").cookie(refreshCookie(s2)))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/refresh").cookie(refreshCookie(s1))).andExpect(status().isOk());

    assertThat(login(EXEC_EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(401);
    assertThat(login(EXEC_EMAIL, "Brand-new1").getResponse().getStatus()).isEqualTo(200);
    String hash =
        jdbc.queryForObject(
            "select password_hash from user_accounts where user_id = 1", String.class);
    assertThat(hash).startsWith("$2");
  }

  @Test
  void undocumentedEndpointsDoNotExist() throws Exception {
    String token = accessToken(login(EXEC_EMAIL, PASSWORD));
    mvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/employees/1/salary/current").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  private void expectPasswordError(
      String token, String current, String next, String code, String message) throws Exception {
    MvcResult r = changePassword(token, current, next);
    assertThat(r.getResponse().getStatus()).as(code).isEqualTo(400);
    JsonNode e = json.readTree(r.getResponse().getContentAsString());
    assertThat(e.get("code").asText()).isEqualTo(code);
    assertThat(e.get("message").asText()).isEqualTo(message);
    assertThat(e.get("field").asText()).isEqualTo("newPassword");
  }

  private MvcResult changePassword(String token, String current, String next) throws Exception {
    return mvc.perform(
            put("/api/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        java.util.Map.of("currentPassword", current, "newPassword", next))))
        .andReturn();
  }

  protected String accessToken(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
  }
}
