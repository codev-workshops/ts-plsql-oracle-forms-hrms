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
 * Full Spring context against the shared Testcontainers PostgreSQL with the frozen seed plus one
 * login account per grade band (STAFF / MANAGER / EXECUTIVE).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AuthApiTestBase {

  protected static final String EXEC_EMAIL = "james.richardson@company.com"; // emp 1, grade 12
  protected static final String STAFF_EMAIL = "sarah.chen@company.com"; // emp 2
  protected static final String PASSWORD = "Welcome1!";

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
    }
    String hash = encoder.encode(PASSWORD);
    account(1, 1, EXEC_EMAIL, hash, 3);
    account(2, 2, STAFF_EMAIL, hash, 1);
  }

  private void account(long userId, long empId, String email, String hash, int roleId) {
    jdbc.update(
        "insert into user_accounts (user_id, emp_id, username, password_hash, created_by)"
            + " values (?, ?, ?, ?, 'TEST')",
        userId,
        empId,
        email,
        hash);
    jdbc.update(
        "insert into user_roles (user_id, role_id, granted_by) values (?, ?, 'TEST')",
        userId,
        roleId);
  }

  protected static Cookie refreshCookie(MvcResult result) {
    Cookie c = result.getResponse().getCookie("hrms_refresh");
    if (c == null) {
      throw new AssertionError("no hrms_refresh cookie");
    }
    return c;
  }
}
