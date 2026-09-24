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

    // staff (emp 2, EMPLOYEE:VIEW) reads any employee's basic detail; ssnLast4 is EDIT/self-only
    mvc.perform(get("/api/employees/2").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ssnLast4").doesNotExist());
    mvc.perform(get("/api/employees/" + id).header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.ssnLast4").doesNotExist());
    mvc.perform(get("/api/employees/" + id + "/history").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].changeType").value("HIRE"))
        .andExpect(jsonPath("$[0].newSalary").doesNotExist());
    mvc.perform(
            get("/api/employees/" + id + "/dependents").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            get("/api/employees/" + id + "/contacts").header("Authorization", "Bearer " + staff))
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

  /**
   * LIFECYCLE-02 / {@code additionalProperties: false}: any property outside the request schema is
   * {@code 400 VALIDATION_FAILED}, whatever its value, and nothing is written.
   */
  @Test
  void unknownRequestPropertiesAreRejectedWithoutWriting() throws Exception {
    MvcResult created =
        mvc.perform(json(post("/api/employees"), exec, newEmployee("api.strict@company.com")))
            .andExpect(status().isCreated())
            .andReturn();
    long id = body(created).get("id").asLong();
    String etag = created.getResponse().getHeader("ETag");
    int audits = jdbc.queryForObject("select count(*) from audit_log", Integer.class);
    int history = jdbc.queryForObject("select count(*) from employee_history", Integer.class);

    Map<String, Object> frozen = update("STRICT", "api.strict@company.com");
    frozen.put("hireDate", "2020-01-01");
    frozen.put("employmentStatus", "TERMINATED");
    mvc.perform(json(put("/api/employees/" + id), exec, frozen).header("If-Match", etag))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("hireDate"))
        .andExpect(jsonPath("$.details[0].field").value("hireDate"))
        .andExpect(jsonPath("$.details[0].code").value("UnknownProperty"))
        .andExpect(jsonPath("$.traceId").isString());

    for (String property : new String[] {"empNumber", "deptId", "terminationDate", "activeFlag"}) {
      Map<String, Object> unknown = update("STRICT", "api.strict@company.com");
      unknown.put(property, null);
      mvc.perform(json(put("/api/employees/" + id), exec, unknown).header("If-Match", etag))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.field").value(property));
    }

    // error-codes.md §3: authority, required If-Match and -20010 all win over the unknown property
    mvc.perform(json(put("/api/employees/" + id), staff, frozen).header("If-Match", etag))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(json(put("/api/employees/" + id), exec, frozen))
        .andExpect(status().isPreconditionRequired())
        .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    Map<String, Object> blankName = update("", "api.strict@company.com");
    blankName.put("hireDate", "2020-01-01");
    mvc.perform(json(put("/api/employees/" + id), exec, blankName).header("If-Match", etag))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20010"))
        .andExpect(jsonPath("$.field").value("firstName"));
    Map<String, Object> blankCreate = newEmployee("api.strict3@company.com");
    blankCreate.put("lastName", " ");
    blankCreate.put("empNumber", "EMP-999999");
    mvc.perform(json(post("/api/employees"), exec, blankCreate))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20010"))
        .andExpect(jsonPath("$.field").value("lastName"));
    // ... and the unknown property still wins over generic Bean Validation of known fields
    Map<String, Object> badEmail = update("STRICT", "not-an-email");
    badEmail.put("hireDate", "2020-01-01");
    mvc.perform(json(put("/api/employees/" + id), exec, badEmail).header("If-Match", etag))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("hireDate"));

    Map<String, Object> salary = new HashMap<>();
    salary.put("baseSalary", "85000.00");
    salary.put("effectiveDate", "2025-07-01");
    salary.put("changeReason", "MERIT");
    salary.put("changePct", null);
    int salaries = jdbc.queryForObject("select count(*) from salary_records", Integer.class);
    mvc.perform(json(post("/api/employees/" + id + "/salary"), staff, salary))
        .andExpect(status().isForbidden());
    mvc.perform(json(post("/api/employees/" + id + "/salary"), exec, salary))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("changePct"))
        .andExpect(jsonPath("$.details[0].code").value("UnknownProperty"));
    assertThat(jdbc.queryForObject("select count(*) from salary_records", Integer.class))
        .isEqualTo(salaries);

    mvc.perform(get("/api/employees/" + id).header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", etag))
        .andExpect(jsonPath("$.firstName").value("API"))
        .andExpect(jsonPath("$.hireDate").value("2025-06-02"))
        .andExpect(jsonPath("$.employmentStatus").value("ACTIVE"));
    assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class))
        .isEqualTo(audits);
    assertThat(jdbc.queryForObject("select count(*) from employee_history", Integer.class))
        .isEqualTo(history);

    // the same rule on create and on the nested dependent / contact schemas
    Map<String, Object> create = newEmployee("api.strict2@company.com");
    create.put("empNumber", "EMP-999999");
    mvc.perform(json(post("/api/employees"), exec, create))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("empNumber"));
    create = newEmployee("api.strict2@company.com");
    create.put("employmentStatus", null);
    mvc.perform(json(post("/api/employees"), exec, create))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("employmentStatus"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from employees where lower(email) = 'api.strict2@company.com'",
                Integer.class))
        .isZero();

    Map<String, Object> dep = new HashMap<>(dependent("A"));
    dep.put("empId", 1);
    mvc.perform(json(post("/api/employees/" + id + "/dependents"), exec, dep))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("empId"));
    Map<String, Object> contact = new HashMap<>(contact());
    contact.put("contactId", 7);
    mvc.perform(json(post("/api/employees/" + id + "/contacts"), exec, contact))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("contactId"));
    Map<String, Object> terminate = new HashMap<>();
    terminate.put("effectiveDate", "2025-06-30");
    terminate.put("reason", "VOLUNTARY");
    terminate.put("employmentStatus", "TERMINATED");
    mvc.perform(json(post("/api/employees/" + id + "/terminate"), exec, terminate))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            json(
                post("/api/employees/" + id + "/transfer"),
                exec,
                Map.of("deptId", 30, "effectiveDate", "2025-07-01", "hireDate", "2025-07-01")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("hireDate"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from employee_dependents where emp_id = " + id, Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from emergency_contacts where emp_id = " + id, Integer.class))
        .isZero();
    mvc.perform(get("/api/employees/" + id).header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.employmentStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.deptId").value(30))
        .andExpect(jsonPath("$.locationCode").value("CHI"));

    Map<String, Object> login = new HashMap<>();
    login.put("username", EXEC_EMAIL);
    login.put("password", PASSWORD);
    login.put("hireDate", null);
    mvc.perform(json(post("/api/auth/login"), null, login)).andExpect(status().isOk());
  }

  /** openapi.yaml listEmployees: {@code hireDateFrom > hireDateTo} reports {@code hireDateTo}. */
  @Test
  void reversedHireDateRangeReportsHireDateTo() throws Exception {
    mvc.perform(
            get("/api/employees?hireDateFrom=2025-06-30&hireDateTo=2025-06-01")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Request validation failed"))
        .andExpect(jsonPath("$.field").value("hireDateTo"))
        .andExpect(jsonPath("$.details.length()").value(1))
        .andExpect(jsonPath("$.details[0].field").value("hireDateTo"))
        .andExpect(
            jsonPath("$.details[0].message")
                .value("hireDateFrom must be before or equal to hireDateTo"))
        .andExpect(jsonPath("$.traceId").isString());

    for (String range :
        new String[] {
          "hireDateFrom=2025-06-01&hireDateTo=2025-06-30",
          "hireDateFrom=2025-06-01&hireDateTo=2025-06-01",
          "hireDateFrom=2025-06-30",
          "hireDateTo=2025-06-01"
        }) {
      mvc.perform(get("/api/employees?" + range).header("Authorization", "Bearer " + staff))
          .andExpect(status().isOk());
    }

    // an unparsable date is still the generic binding failure on its own parameter
    mvc.perform(
            get("/api/employees?hireDateFrom=2025-13-45&hireDateTo=2025-06-01")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("hireDateFrom"));

    // the other query constraints keep reporting on their own field alongside the range rule
    mvc.perform(
            get("/api/employees?status=RETIRED&hireDateFrom=2025-06-30&hireDateTo=2025-06-01")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("status"));

    // authentication precedes validation
    mvc.perform(get("/api/employees?hireDateFrom=2025-06-30&hireDateTo=2025-06-01"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
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

  /**
   * -20004 names the request property that carried the manager: {@code managerEmpId} on
   * create/update, {@code newManagerEmpId} on transfer (error-codes.md, -20004 row).
   */
  @Test
  void invalidManagerFieldFollowsTheRequestProperty() throws Exception {
    // 99 is TERMINATED in the seed; emp 1 is the root of the chain, so 3 (reports to 1) is circular
    Map<String, Object> inactive = newEmployee("api.manager@company.com");
    inactive.put("managerEmpId", 99);
    mvc.perform(json(post("/api/employees"), exec, inactive))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20004"))
        .andExpect(jsonPath("$.message").value("Invalid or inactive manager: 99"))
        .andExpect(jsonPath("$.field").value("managerEmpId"));

    String etag =
        mvc.perform(get("/api/employees/1").header("Authorization", "Bearer " + exec))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    Map<String, Object> circular = update("JAMES", EXEC_EMAIL);
    circular.put("managerEmpId", 3);
    mvc.perform(json(put("/api/employees/1"), exec, circular).header("If-Match", etag))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20004"))
        .andExpect(
            jsonPath("$.message")
                .value("Circular reporting chain detected: Employee 1 cannot report to 3"))
        .andExpect(jsonPath("$.field").value("managerEmpId"));

    mvc.perform(
            json(
                post("/api/employees/1/transfer"),
                exec,
                Map.of("deptId", 30, "effectiveDate", "2025-07-01", "newManagerEmpId", 99)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20004"))
        .andExpect(jsonPath("$.message").value("Invalid or inactive manager: 99"))
        .andExpect(jsonPath("$.field").value("newManagerEmpId"));
    mvc.perform(
            json(
                post("/api/employees/1/transfer"),
                exec,
                Map.of("deptId", 30, "effectiveDate", "2025-07-01", "newManagerEmpId", 3)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20004"))
        .andExpect(
            jsonPath("$.message")
                .value("Circular reporting chain detected: Employee 1 cannot report to 3"))
        .andExpect(jsonPath("$.field").value("newManagerEmpId"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from employee_history where emp_id = 1 and change_type = 'TRANSFER'",
                Integer.class))
        .isZero();
  }

  @Test
  void staffCannotCreateEmployeesButSelfServesDependentsAndContacts() throws Exception {
    mvc.perform(json(post("/api/employees"), staff, newEmployee("api.staff@company.com")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    // staff (emp 2, EMPLOYEE:VIEW) writes its own rows, HR (EMPLOYEE:EDIT) writes anyone's
    MvcResult dep =
        mvc.perform(json(post("/api/employees/2/dependents"), staff, dependent("A")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.firstName").value("A"))
            .andReturn();
    long dependentId = body(dep).get("dependentId").asLong();
    mvc.perform(json(put("/api/employees/2/dependents/" + dependentId), staff, dependent("B")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firstName").value("B"));
    mvc.perform(json(post("/api/employees/2/contacts"), staff, contact()))
        .andExpect(status().isCreated());
    mvc.perform(json(post("/api/employees/1/dependents"), staff, dependent("X")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(json(post("/api/employees/1/contacts"), staff, contact()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(json(put("/api/employees/1/dependents/" + dependentId), staff, dependent("X")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DEPENDENT_NOT_FOUND"));
    mvc.perform(json(post("/api/employees/2/dependents"), exec, dependent("HR")))
        .andExpect(status().isCreated());
  }

  @Test
  void employeeRoutesRequireEmployeeViewAuthority() throws Exception {
    jdbc.update("delete from role_permissions where role_id = 1 and authority = 'EMPLOYEE:VIEW'");
    try {
      String noView = token(STAFF_EMAIL);
      for (String path :
          new String[] {
            "/api/employees",
            "/api/employees/2",
            "/api/employees/2/history",
            "/api/employees/2/dependents",
            "/api/employees/2/contacts"
          }) {
        mvc.perform(get(path).header("Authorization", "Bearer " + noView))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
      }
      mvc.perform(json(post("/api/employees/2/dependents"), noView, dependent("A")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
      mvc.perform(json(post("/api/employees/2/contacts"), noView, contact()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    } finally {
      jdbc.update("insert into role_permissions (role_id, authority) values (1, 'EMPLOYEE:VIEW')");
    }
  }

  static Map<String, Object> dependent(String firstName) {
    return Map.of("firstName", firstName, "lastName", "CHEN", "relationship", "CHILD");
  }

  static Map<String, Object> contact() {
    return Map.of(
        "contactName", "Pat Chen", "relationship", "SPOUSE", "phonePrimary", "3125550100");
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
