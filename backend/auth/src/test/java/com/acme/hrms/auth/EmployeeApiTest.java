package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Employee routes through the wired auth application: JWT identity, module flag, If-Match, the
 * -20504 DELETE divergence, and the COMPONENT_MAPPING.md §3.2 termination side effects (every
 * session of the terminated employee revoked, account disabled).
 */
@TestPropertySource(properties = "hrms.proxy.modules.employee=NEW")
class EmployeeApiTest extends AuthApiTestBase {

  private static final String EMILY_EMAIL = "emily.johnson@company.com"; // emp 12, user 5

  private String exec;
  private String staff;

  @BeforeEach
  void seed() throws Exception {
    seedAccounts();
    jdbc.update(
        "update employees set employment_status = 'ACTIVE', active_flag = 'Y',"
            + " termination_date = null, termination_reason = null where emp_id in (1, 12)");
    jdbc.update("update user_accounts set status = 'ACTIVE' where emp_id = 12");
    exec = token(EXEC_EMAIL);
    staff = token(STAFF_EMAIL);
  }

  @Test
  void createReadUpdateWithEtagAndScope() throws Exception {
    MvcResult created =
        mvc.perform(json(post("/api/employees"), exec, newEmployee("api.create@company.com")))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(header().exists("ETag"))
            .andExpect(
                jsonPath("$.empNumber").value(org.hamcrest.Matchers.matchesPattern("EMP-\\d{6}")))
            .andExpect(jsonPath("$.employmentStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.ssnLast4").value("6789"))
            .andExpect(jsonPath("$.ssn").doesNotExist())
            .andReturn();
    long id = body(created).get("id").asLong();
    String etag = created.getResponse().getHeader("ETag");

    // staff (emp 2) may read itself but not another employee
    mvc.perform(get("/api/employees/2").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ssnLast4").doesNotExist());
    mvc.perform(get("/api/employees/" + id).header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    // If-Match is required, then optimistic locking
    Map<String, Object> upd = update("APIUPD", "api.create@company.com");
    mvc.perform(json(put("/api/employees/" + id), exec, upd))
        .andExpect(status().isPreconditionRequired())
        .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    mvc.perform(json(put("/api/employees/" + id), exec, upd).header("If-Match", etag))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firstName").value("APIUPD"))
        .andExpect(header().string("ETag", "\"1\""));
    mvc.perform(json(put("/api/employees/" + id), exec, upd).header("If-Match", etag))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));

    // history: HIRE only (update without job change writes no row)
    mvc.perform(get("/api/employees/" + id + "/history").header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].changeType").value("HIRE"));

    // e-mail uniqueness is case-insensitive
    mvc.perform(json(post("/api/employees"), exec, newEmployee("API.CREATE@company.com")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20502"));
  }

  @Test
  void deleteIsRefusedWithTheTriggerCode() throws Exception {
    mvc.perform(delete("/api/employees/2").header("Authorization", "Bearer " + exec))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("-20504"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "Direct deletion not allowed. Use termination process or set ACTIVE_FLAG to N."));
    assertThat(
            jdbc.queryForObject("select count(*) from employees where emp_id = 2", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void terminationRevokesEverySessionDisablesTheAccountAndIsIdempotentlyRefused() throws Exception {
    String emily1 = token(EMILY_EMAIL);
    String emily2 = token(EMILY_EMAIL);
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + emily1))
        .andExpect(status().isOk());

    mvc.perform(
            json(
                post("/api/employees/12/terminate"),
                exec,
                Map.of("effectiveDate", "2025-06-30", "reason", "VOLUNTARY")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.employmentStatus").value("TERMINATED"))
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.terminationDate").value("2025-06-30"));

    for (String t : new String[] {emily1, emily2}) {
      mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_sessions where emp_id = 12 and logout_time is null",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject("select status from user_accounts where emp_id = 12", String.class))
        .isEqualTo("DISABLED");
    mvc.perform(
            json(
                post("/api/auth/login"),
                null,
                Map.of("username", EMILY_EMAIL, "password", PASSWORD)))
        .andExpect(status().isUnauthorized());

    mvc.perform(
            json(
                post("/api/employees/12/terminate"),
                exec,
                Map.of("effectiveDate", "2025-07-01", "reason", "VOLUNTARY")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20005"))
        .andExpect(jsonPath("$.message").value("Employee 12 is already terminated"));
    mvc.perform(
            json(
                post("/api/employees/12/transfer"),
                exec,
                Map.of("deptId", 30, "effectiveDate", "2025-07-01")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20012"));
    assertThat(
            jdbc.queryForObject("select count(*) from employees where emp_id = 12", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void staffCannotWriteAndReadOnlyFlagBlocksWrites() throws Exception {
    mvc.perform(json(post("/api/employees"), staff, newEmployee("api.staff@company.com")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            json(
                post("/api/employees/2/dependents"),
                staff,
                Map.of("firstName", "A", "lastName", "B", "relationship", "CHILD")))
        .andExpect(status().isForbidden());
  }

  private static Map<String, Object> newEmployee(String email) {
    Map<String, Object> m = new HashMap<>();
    m.put("firstName", "API");
    m.put("lastName", "CREATE");
    m.put("email", email);
    m.put("hireDate", "2025-06-02");
    m.put("deptId", 30);
    m.put("jobId", 50);
    m.put("managerEmpId", 31);
    m.put("locationCode", "CHI");
    m.put("employmentType", "FULL_TIME");
    m.put("ssn", "123-45-6789");
    m.put("initialSalary", 85000);
    return m;
  }

  private static Map<String, Object> update(String firstName, String email) {
    Map<String, Object> m = new HashMap<>();
    m.put("firstName", firstName);
    m.put("lastName", "CREATE");
    m.put("email", email);
    m.put("jobId", 50);
    m.put("managerEmpId", 31);
    m.put("locationCode", "CHI");
    m.put("employmentType", "FULL_TIME");
    return m;
  }

  private String token(String email) throws Exception {
    return body(mvc.perform(
                json(
                    post("/api/auth/login"), null, Map.of("username", email, "password", PASSWORD)))
            .andReturn())
        .get("accessToken")
        .asText();
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String token, Object value) throws Exception {
    request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return request;
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
