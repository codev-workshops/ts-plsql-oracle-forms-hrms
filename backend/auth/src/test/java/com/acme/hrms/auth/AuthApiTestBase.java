package com.acme.hrms.auth;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Full Spring context against the shared Testcontainers PostgreSQL with the frozen seed. Login
 * accounts come from tools/fixtures/pg/04_user_accounts.sql (one per grade band: STAFF / MANAGER /
 * EXECUTIVE); {@link #seedAccounts()} restores that fixture so tests that mutate passwords or lock
 * accounts start from the committed state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AuthApiTestBase {

  protected static final String EXEC_EMAIL = "james.richardson@company.com"; // emp 1, user 1
  protected static final String MANAGER_EMAIL = "jennifer.park@company.com"; // emp 21, user 3
  protected static final String STAFF_EMAIL = "sarah.chen@company.com"; // emp 2, user 2
  protected static final String PASSWORD = "Welcome1!";
  protected static final String ACCOUNTS_FIXTURE = "04_user_accounts.sql";

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected PasswordEncoder encoder;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry r) {
    PostgreSQLContainer<?> c = HrmsPostgres.container();
    r.add("spring.datasource.url", c::getJdbcUrl);
    r.add("spring.datasource.username", c::getUsername);
    r.add("spring.datasource.password", c::getPassword);
  }

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
  }

  protected void seedAccounts() {
    jdbc.update("delete from refresh_tokens");
    jdbc.update("delete from revoked_jti");
    jdbc.update("delete from user_sessions");
    jdbc.update("delete from user_roles");
    jdbc.update("delete from user_accounts");
    jdbc.update("delete from audit_log");
    Integer emps = jdbc.queryForObject("select count(*) from employees", Integer.class);
    if (emps == null || emps == 0) {
      HrmsPostgres.loadFixtures(jdbc);
    } else {
      HrmsPostgres.loadFixture(jdbc, ACCOUNTS_FIXTURE);
    }
  }

  protected static Cookie refreshCookie(MvcResult result) {
    Cookie c = result.getResponse().getCookie("hrms_refresh");
    if (c == null) {
      throw new AssertionError("no hrms_refresh cookie");
    }
    return c;
  }
}
