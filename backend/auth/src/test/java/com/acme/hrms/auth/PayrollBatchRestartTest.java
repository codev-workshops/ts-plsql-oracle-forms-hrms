package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.hrms.payroll.run.PayrollDetailRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;

/**
 * CUTOVER_PLAN.md §8 / TEST_STRATEGY.md §5: a calculation that dies mid-run leaves the run in
 * ERROR, and POST /calculate again restarts the same Spring Batch job instance (same job
 * parameters) instead of starting a new one, ending with exactly one detail set per employee.
 */
class PayrollBatchRestartTest extends AuthApiTestBase {

  @SpyBean private PayrollDetailRepository details;

  private String exec;

  @BeforeEach
  void seed() throws Exception {
    reset(details);
    seedAccounts();
    jdbc.update("delete from payroll_shadow_reports");
    jdbc.update("delete from payroll_details_shadow");
    jdbc.update("delete from payroll_details where run_id not in (9001, 9002)");
    jdbc.update("delete from payroll_runs where run_id not in (9001, 9002)");
    jdbc.update("update pay_periods set status = 'OPEN' where period_id = 202406");
    exec = token(EXEC_EMAIL);
  }

  @Test
  void failedCalculationIsErrorAndRecalculateRestartsTheSameJobInstance() throws Exception {
    long runId =
        body(mvc.perform(
                    post("/api/payroll/periods/202406/runs")
                        .header("Authorization", "Bearer " + exec)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("runType", "REGULAR"))))
                .andExpect(status().isCreated())
                .andReturn())
            .path("runId")
            .asLong();

    doThrow(new IllegalStateException("simulated node crash while writing shadow rows"))
        .when(details)
        .insertShadow(anyList(), anyString());
    mvc.perform(
            post("/api/payroll/runs/" + runId + "/calculate")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isAccepted());
    JsonNode failed = awaitTerminal(runId);
    assertThat(failed.path("status").asText()).isEqualTo("ERROR");
    assertThat(failed.path("failureMessage").asText()).contains("simulated node crash");
    long firstExecution = failed.path("jobExecutionId").asLong();
    assertThat(countDetails(runId)).as("failed chunk rolled back").isZero();

    reset(details);
    mvc.perform(
            post("/api/payroll/runs/" + runId + "/calculate")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CALCULATING"));
    JsonNode done = awaitTerminal(runId);
    assertThat(done.path("status").asText()).isEqualTo("CALCULATED");
    assertThat(done.path("processed").asInt()).isEqualTo(23);
    assertThat(done.path("errorCount").asInt()).isZero();
    long secondExecution = done.path("jobExecutionId").asLong();
    assertThat(secondExecution).isNotEqualTo(firstExecution);

    Long firstInstance = jobInstanceOf(firstExecution);
    assertThat(jobInstanceOf(secondExecution))
        .as("restart, not a new job instance")
        .isEqualTo(firstInstance);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from batch_job_execution where job_instance_id = ?",
                Integer.class,
                firstInstance))
        .isEqualTo(2);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from (select emp_id, element_id from payroll_details"
                    + " where run_id = ? group by emp_id, element_id having count(*) > 1) d",
                Integer.class,
                runId))
        .as("no duplicated detail rows after restart")
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(distinct emp_id) from payroll_details where run_id = ?",
                Integer.class,
                runId))
        .isEqualTo(23);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payroll_details_shadow where run_id = ? and engine = 'JAVA'",
                Integer.class,
                runId))
        .isEqualTo(countDetails(runId));
  }

  private int countDetails(long runId) {
    return jdbc.queryForObject(
        "select count(*) from payroll_details where run_id = ?", Integer.class, runId);
  }

  private Long jobInstanceOf(long executionId) {
    return jdbc.queryForObject(
        "select job_instance_id from batch_job_execution where job_execution_id = ?",
        Long.class,
        executionId);
  }

  private JsonNode awaitTerminal(long runId) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (true) {
      JsonNode st =
          body(
              mvc.perform(
                      get("/api/payroll/runs/" + runId + "/status")
                          .header("Authorization", "Bearer " + exec))
                  .andExpect(status().isOk())
                  .andReturn());
      if (!Set.of("CALCULATING", "PENDING").contains(st.path("status").asText())) {
        return st;
      }
      assertThat(Instant.now()).as("calculation did not finish").isBefore(deadline);
      Thread.sleep(200);
    }
  }

  private String token(String email) throws Exception {
    return body(mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsBytes(Map.of("username", email, "password", PASSWORD))))
            .andReturn())
        .get("accessToken")
        .asText();
  }

  private JsonNode body(org.springframework.test.web.servlet.MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }
}
