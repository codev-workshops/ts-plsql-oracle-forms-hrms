package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Level 1: the committed login fixture (tools/fixtures/pg/04_user_accounts.sql) alone - no
 * test-local inserts - lets one user of every grade band authenticate with the documented password.
 */
class SeededAccountsTest extends AuthApiTestBase {

  @BeforeEach
  void seed() {
    seedAccounts();
  }

  @Test
  void fixtureIsCommittedAndCoversEveryRoleBand() throws Exception {
    Path fixture = HrmsPostgres.repoRoot().resolve("tools/fixtures/pg").resolve(ACCOUNTS_FIXTURE);
    assertThat(fixture).exists();
    assertThat(Files.readString(fixture)).contains("Welcome1!");
    List<String> roles =
        jdbc.queryForList(
            "select distinct r.role_code from user_roles ur join roles r on r.role_id = ur.role_id"
                + " where ur.granted_by = 'SEED' order by 1",
            String.class);
    assertThat(roles).containsExactly("EXECUTIVE", "MANAGER", "STAFF");
  }

  @Test
  void everyRoleBandLogsInWithTheFixturePassword() throws Exception {
    assertLogin(EXEC_EMAIL, 1, "ADMIN:APPROVE");
    assertLogin(MANAGER_EMAIL, 21, "PAYROLL:VIEW");
    assertLogin(STAFF_EMAIL, 2, "EMPLOYEE:VIEW");
    assertThat(login("emily.johnson@company.com").getResponse().getContentAsString())
        .contains("\"mustChangePassword\":true");
  }

  private void assertLogin(String email, long empId, String expectedAuthority) throws Exception {
    MvcResult r = login(email);
    assertThat(r.getResponse().getStatus()).as(email).isEqualTo(200);
    JsonNode user = json.readTree(r.getResponse().getContentAsString()).get("user");
    assertThat(user.get("empId").asLong()).isEqualTo(empId);
    assertThat(user.get("roles").toString()).contains(expectedAuthority);
  }

  private MvcResult login(String email) throws Exception {
    return mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
        .andReturn();
  }
}
