package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.hrms.salary.SalaryRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@TestPropertySource(properties = "hrms.proxy.modules.employee=NEW")
class SalaryApiTest extends AuthApiTestBase {

  @SpyBean private SalaryRecordRepository records;

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

  @Test
  void concurrentSalaryPostsReturnConflictWithoutRollingBackTheWinner() throws Exception {
    assertConcurrentSalaryOutcome();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 1 and active_flag = 'Y'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 1", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where table_name = 'SALARY_RECORDS' and action_type in"
                    + " ('UPDATE', 'INSERT')",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void concurrentFirstSalaryPostsAlsoReturnConflict() throws Exception {
    jdbc.update("delete from salary_records where emp_id = 1");
    assertConcurrentSalaryOutcome();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 1 and active_flag = 'Y'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 1", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where table_name = 'SALARY_RECORDS' and action_type ="
                    + " 'INSERT'",
                Integer.class))
        .isEqualTo(1);
  }

  private void assertConcurrentSalaryOutcome() throws Exception {
    CyclicBarrier bothReadPrevious = new CyclicBarrier(2);
    AtomicInteger firstReads = new AtomicInteger();
    doAnswer(
            invocation -> {
              Object previous = invocation.callRealMethod();
              if (invocation.getArgument(0, Long.class) == 1L
                  && firstReads.incrementAndGet() <= 2) {
                bothReadPrevious.await(10, TimeUnit.SECONDS);
              }
              return previous;
            })
        .when(records)
        .findActive(anyLong());

    Callable<MvcResult> change =
        () ->
            mvc.perform(
                    json(
                        post("/api/employees/1/salary"),
                        exec,
                        Map.of(
                            "effectiveDate", "2025-01-01",
                            "baseSalary", 110000,
                            "changeReason", "MERIT")))
                .andReturn();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<MvcResult> first = pool.submit(change);
      Future<MvcResult> second = pool.submit(change);
      List<MvcResult> results =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertThat(results)
          .extracting(result -> result.getResponse().getStatus())
          .containsExactlyInAnyOrder(201, 409);
      for (MvcResult result : results) {
        if (result.getResponse().getStatus() == 409) {
          assertThat(body(result).get("code").asText()).isEqualTo("CONFLICT");
        }
      }
    } finally {
      pool.shutdownNow();
      reset(records);
    }
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
