package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@TestPropertySource(properties = "hrms.proxy.modules.employee=NEW")
class SalaryApiTest extends AuthApiTestBase {

  private String exec;
  private String staff;

  @BeforeEach
  void seed() throws Exception {
    seedAccounts();
    jdbc.update("update employees set employment_status = 'ACTIVE' where emp_id = 1");
    jdbc.update("delete from salary_records where emp_id = 1");
    jdbc.update(
        "insert into salary_records (salary_id, emp_id, effective_date, base_salary,"
            + " currency_code, pay_frequency, salary_basis, change_reason, active_flag,"
            + " out_of_grade_band, created_by) values (nextval('seq_salary'), 1, date '2024-01-01',"
            + " 100000, 'USD', 'MONTHLY', 'ANNUAL', 'SEED', 'Y', 'N', 'test')");
    exec = token(EXEC_EMAIL);
    staff = token(STAFF_EMAIL);
  }

  @Test
  void readsCurrentAndEnforcesEmployeeScope() throws Exception {
    mvc.perform(get("/api/employees/2/salary").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.empId").value(2))
        .andExpect(jsonPath("$.baseSalary").isString());
    mvc.perform(get("/api/employees/1/salary").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(get("/api/employees/1/salary").header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk());
  }

  @Test
  void currentUnknownAndNoActiveAndHistoryEmpty() throws Exception {
    mvc.perform(get("/api/employees/424242/salary").header("Authorization", "Bearer " + exec))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("-20001"));
    jdbc.update("delete from salary_records where emp_id = 1");
    mvc.perform(get("/api/employees/1/salary").header("Authorization", "Bearer " + exec))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20104"));
    mvc.perform(get("/api/employees/1/salary/history").header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void changesSalaryWithValidationOrderingAuditAndOutOfBandFlag() throws Exception {
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2025-01-01",
                    "baseSalary", 0,
                    "changeReason", "MERIT")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20101"))
        .andExpect(jsonPath("$.message").value("Salary must be positive: 0"))
        .andExpect(jsonPath("$.field").value("baseSalary"));

    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2025-01-01",
                    "baseSalary", 110000,
                    "changeReason", "MERIT")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.baseSalary").value("110000.00"))
        .andExpect(jsonPath("$.changePct").value("10.00"))
        .andExpect(jsonPath("$.active").value(true));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where table_name = 'SALARY_RECORDS' and action_type in"
                    + " ('UPDATE', 'INSERT')",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select end_date from salary_records where emp_id = 1 and active_flag = 'N'"
                    + " order by salary_id desc limit 1",
                java.sql.Date.class))
        .isEqualTo(java.sql.Date.valueOf("2025-01-01"));

    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2026-01-01",
                    "baseSalary", 999999,
                    "changeReason", "PROMOTION")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.outOfGradeBand").value(true));
  }

  /** x-history [SALARY_CHANGE]: one employee_history row per accepted change, none on rejection. */
  @Test
  void changeWritesSalaryChangeHistoryWithOldNewReasonAndActor() throws Exception {
    jdbc.update("delete from employee_history where emp_id = 1");
    jdbc.update("delete from salary_records where emp_id = 1");

    // no prior active salary: old_salary is null
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2025-01-01",
                    "baseSalary", 100000,
                    "changeReason", "INITIAL")))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2025-06-01",
                    "baseSalary", 110000,
                    "changeReason", "MERIT")))
        .andExpect(status().isCreated());
    // rejected (before hire date / previous effective date): nothing written
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2025-01-15",
                    "baseSalary", 120000,
                    "changeReason", "MERIT")))
        .andExpect(status().isBadRequest());

    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 1", Integer.class))
        .isEqualTo(2);
    String actor =
        jdbc.queryForObject(
            "select created_by from salary_records where emp_id = 1 and active_flag = 'Y'",
            String.class);
    assertThat(actor).isNotBlank();
    assertThat(
            jdbc.queryForList(
                "select change_type, old_salary, new_salary, reason_code, created_by,"
                    + " old_dept_id, new_job_id from employee_history where emp_id = 1"
                    + " order by hist_id"))
        .satisfiesExactly(
            first -> {
              assertThat(first.get("change_type")).isEqualTo("SALARY_CHANGE");
              assertThat(first.get("old_salary")).isNull();
              assertThat((java.math.BigDecimal) first.get("new_salary"))
                  .isEqualByComparingTo("100000");
              assertThat(first.get("reason_code")).isEqualTo("INITIAL");
              assertThat(first.get("created_by")).isEqualTo(actor);
              assertThat(first.get("old_dept_id")).isNull();
              assertThat(first.get("new_job_id")).isNull();
            },
            second -> {
              assertThat(second.get("change_type")).isEqualTo("SALARY_CHANGE");
              assertThat((java.math.BigDecimal) second.get("old_salary"))
                  .isEqualByComparingTo("100000");
              assertThat((java.math.BigDecimal) second.get("new_salary"))
                  .isEqualByComparingTo("110000");
              assertThat(second.get("reason_code")).isEqualTo("MERIT");
              assertThat(second.get("created_by")).isEqualTo(actor);
            });

    // history API: salary read scope sees amounts, plain EMPLOYEE:VIEW gets them masked
    mvc.perform(get("/api/employees/1/history").header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].changeType").value("SALARY_CHANGE"))
        .andExpect(jsonPath("$[0].oldSalary").value("100000.00"))
        .andExpect(jsonPath("$[0].newSalary").value("110000.00"))
        .andExpect(jsonPath("$[0].reasonCode").value("MERIT"))
        .andExpect(jsonPath("$[1].oldSalary").doesNotExist())
        .andExpect(jsonPath("$[1].newSalary").value("100000.00"));
    mvc.perform(get("/api/employees/1/history").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].changeType").value("SALARY_CHANGE"))
        .andExpect(jsonPath("$[0].oldSalary").doesNotExist())
        .andExpect(jsonPath("$[0].newSalary").doesNotExist());
  }

  /** POST /api/employees with initialSalary is x-history [HIRE] only, never SALARY_CHANGE. */
  @Test
  void initialSalaryOnHireWritesHireHistoryOnly() throws Exception {
    long id =
        body(mvc.perform(
                    json(
                        post("/api/employees"),
                        exec,
                        Map.ofEntries(
                            Map.entry("firstName", "Sal"),
                            Map.entry("lastName", "Hist"),
                            Map.entry("email", "sal.hist@company.com"),
                            Map.entry("hireDate", "2025-06-02"),
                            Map.entry("deptId", 30),
                            Map.entry("jobId", 50),
                            Map.entry("managerEmpId", 31),
                            Map.entry("locationCode", "CHI"),
                            Map.entry("employmentType", "FULL_TIME"),
                            Map.entry("ssn", "123-45-6789"),
                            Map.entry("initialSalary", 85000))))
                .andExpect(status().isCreated())
                .andReturn())
            .get("id")
            .asLong();
    assertThat(
            jdbc.queryForList(
                "select change_type from employee_history where emp_id = ? order by hist_id",
                String.class,
                id))
        .containsExactly("HIRE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = ?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void omittedBaseSalaryUsesBeanValidation() throws Exception {
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of("effectiveDate", "2025-01-01", "changeReason", "MERIT")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("baseSalary"));
  }

  @Test
  void rejectsDateStatusAndOtherBodyErrors() throws Exception {
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2023-01-01",
                    "baseSalary", 110000,
                    "changeReason", "MERIT")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("effectiveDate"));
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2025-01-01",
                    "baseSalary", 110000,
                    "currencyCode", "usd",
                    "changeReason", "MERIT")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("currencyCode"));
    jdbc.update("update employees set employment_status = 'TERMINATED' where emp_id = 1");
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                exec,
                Map.of(
                    "effectiveDate", "2025-01-01",
                    "baseSalary", 110000,
                    "changeReason", "MERIT")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("-20001"));
  }

  @Test
  void staffCannotWrite() throws Exception {
    mvc.perform(
            json(
                post("/api/employees/1/salary"),
                staff,
                Map.of(
                    "effectiveDate", "2025-01-01",
                    "baseSalary", 110000,
                    "changeReason", "MERIT")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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

  private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
