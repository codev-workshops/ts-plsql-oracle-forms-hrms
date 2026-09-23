package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Level 1 for the admin module: PostgreSQL ownership of the reference tables, the -20601 … -20606
 * validation codes of error-codes.md, soft deactivation, audit rows keyed by the JWT actor, the
 * accrual / carryover job triggers (-20702) and the P2 301 aliases.
 */
class AdminReferenceApiTest extends AuthApiTestBase {

  /** JWT {@code sub} of the EXECUTIVE account (user 1) – the actor recorded on every write. */
  private static final String EXEC_USER = "1";

  private String exec;
  private String manager;
  private String staff;

  @BeforeEach
  void seed() throws Exception {
    HrmsPostgres.resetSchema();
    seedAccounts();
    exec = token(EXEC_EMAIL);
    manager = token(MANAGER_EMAIL);
    staff = token(STAFF_EMAIL);
  }

  @Test
  void departmentsCrudCodesAndCycleDetection() throws Exception {
    mvc.perform(get("/api/admin/departments").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(10));
    jdbc.update("update departments set active_flag = 'N' where dept_id = 70");
    mvc.perform(
            get("/api/admin/departments?active=true").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(9));
    mvc.perform(
            get("/api/admin/departments?active=false").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].deptCode").value("LEGAL"));
    mvc.perform(get("/api/admin/departments").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(10));
    mvc.perform(get("/api/admin/departments").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());

    String body =
        json(
            Map.of(
                "deptCode", "LGL",
                "deptName", "Legal",
                "parentDeptId", 1,
                "costCenter", "CC-1900",
                "managerEmpId", 2,
                "locationCode", "HQ"));
    // ADMIN:EDIT required
    mvc.perform(jsonReq(post("/api/admin/departments"), manager, body))
        .andExpect(status().isForbidden());
    JsonNode created =
        body(
            mvc.perform(jsonReq(post("/api/admin/departments"), exec, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deptCode").value("LGL"))
                .andExpect(jsonPath("$.parentDeptName").value("Executive Office"))
                .andExpect(jsonPath("$.managerName").value("SARAH CHEN"))
                .andExpect(jsonPath("$.activeFlag").value(true))
                .andExpect(jsonPath("$.createdBy").value(EXEC_USER))
                .andReturn());
    long id = created.get("deptId").asLong();
    assertThat(
            jdbc.queryForObject(
                "select changed_by from audit_log where table_name='DEPARTMENTS' and record_id=?"
                    + " and action_type='INSERT'",
                String.class,
                id))
        .isEqualTo(EXEC_USER);

    // -20601 duplicate code
    mvc.perform(jsonReq(post("/api/admin/departments"), exec, body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20601"))
        .andExpect(jsonPath("$.field").value("deptCode"));
    // -20601 immutable code on PUT
    mvc.perform(
            jsonReq(
                put("/api/admin/departments/" + id),
                exec,
                json(Map.of("deptCode", "LGL2", "deptName", "Legal", "locationCode", "HQ"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20601"));
    // -20003 inactive parent (dept 70 deactivated above), -20604 unknown location,
    // -20001 inactive manager, -20605 self-parent
    mvc.perform(
            jsonReq(
                put("/api/admin/departments/" + id),
                exec,
                json(Map.of("deptCode", "LGL", "deptName", "Legal", "parentDeptId", 70))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20003"))
        .andExpect(jsonPath("$.field").value("parentDeptId"))
        .andExpect(jsonPath("$.message").value("Invalid or inactive department: 70"));
    mvc.perform(
            jsonReq(
                put("/api/admin/departments/" + id),
                exec,
                json(Map.of("deptCode", "LGL", "deptName", "Legal", "locationCode", "MARS"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20604"))
        .andExpect(jsonPath("$.field").value("locationCode"));
    mvc.perform(
            jsonReq(
                put("/api/admin/departments/" + id),
                exec,
                json(Map.of("deptCode", "LGL", "deptName", "Legal", "managerEmpId", 4))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("-20001"))
        .andExpect(jsonPath("$.field").value("managerEmpId"));
    mvc.perform(
            jsonReq(
                put("/api/admin/departments/" + id),
                exec,
                json(Map.of("deptCode", "LGL", "deptName", "Legal", "parentDeptId", id))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20605"))
        .andExpect(jsonPath("$.field").value("parentDeptId"));
    // cycle through the chain: make EXEC (1) a child of LEGAL (child of 1)
    mvc.perform(
            jsonReq(
                put("/api/admin/departments/1"),
                exec,
                json(
                    Map.of(
                        "deptCode", "EXEC", "deptName", "Executive Office", "parentDeptId", id))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20605"));

    // update is audited with old/new values
    mvc.perform(
            jsonReq(
                put("/api/admin/departments/" + id),
                exec,
                json(
                    Map.of(
                        "deptCode",
                        "LGL",
                        "deptName",
                        "Legal & Compliance",
                        "locationCode",
                        "HQ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deptName").value("Legal & Compliance"))
        .andExpect(jsonPath("$.modifiedBy").value(EXEC_USER));
    // -20602 department with active employees
    mvc.perform(delete("/api/admin/departments/20").header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20602"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("active")));
    assertThat(
            jdbc.queryForObject(
                "select active_flag from departments where dept_id=20", String.class))
        .isEqualTo("Y");
    // soft deactivation, row stays
    mvc.perform(delete("/api/admin/departments/" + id).header("Authorization", "Bearer " + exec))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select active_flag from departments where dept_id=?", String.class, id))
        .isEqualTo("N");
    mvc.perform(get("/api/admin/departments/" + id).header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeFlag").value(false));
    mvc.perform(get("/api/admin/departments/4242").header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REFERENCE_NOT_FOUND"));
    // validation – frozen DTO rules
    mvc.perform(jsonReq(post("/api/admin/departments"), exec, json(Map.of("deptCode", "x"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void jobGradesAndJobTitlesValueRules() throws Exception {
    String bad =
        json(
            Map.of(
                "gradeCode",
                "G11",
                "gradeName",
                "Fellow",
                "minSalary",
                200000,
                "maxSalary",
                100000));
    // the frozen JobGradeRequest carries the CHK_SALARY_RANGE rule as a bean constraint, so the
    // body validator answers before the service's -20603 check can
    mvc.perform(jsonReq(post("/api/admin/job-grades"), exec, bad))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details[0].field").value("salaryRangeValid"));
    JsonNode g =
        body(
            mvc.perform(
                    jsonReq(
                        post("/api/admin/job-grades"),
                        exec,
                        json(
                            Map.of(
                                "gradeCode",
                                "G11",
                                "gradeName",
                                "Fellow",
                                "minSalary",
                                100000,
                                "maxSalary",
                                200000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.minSalary").value("100000.00"))
                .andExpect(jsonPath("$.overtimeEligible").value(false))
                .andReturn());
    int gradeId = g.get("gradeId").asInt();
    // -20604 inactive/unknown grade on a job title
    mvc.perform(
            jsonReq(
                post("/api/admin/job-titles"),
                exec,
                json(
                    Map.of(
                        "jobCode",
                        "FELLOW",
                        "jobTitle",
                        "Fellow",
                        "gradeId",
                        999,
                        "flsaStatus",
                        "EXEMPT"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20604"))
        .andExpect(jsonPath("$.field").value("gradeId"))
        .andExpect(jsonPath("$.message").value("Invalid or inactive grade: 999"));
    JsonNode jt =
        body(
            mvc.perform(
                    jsonReq(
                        post("/api/admin/job-titles"),
                        exec,
                        json(
                            Map.of(
                                "jobCode", "FELLOW",
                                "jobTitle", "Fellow",
                                "gradeId", gradeId,
                                "flsaStatus", "EXEMPT"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gradeCode").value("G11"))
                .andReturn());
    // grade with an active job title cannot be deactivated (-20602) …
    mvc.perform(
            delete("/api/admin/job-grades/" + gradeId).header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20602"));
    // … job title with active employees neither (job 1 = CEO)
    mvc.perform(delete("/api/admin/job-titles/1").header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20602"));
    // deactivate the title, then the grade
    mvc.perform(
            delete("/api/admin/job-titles/" + jt.get("jobId").asLong())
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isNoContent());
    mvc.perform(
            delete("/api/admin/job-grades/" + gradeId).header("Authorization", "Bearer " + exec))
        .andExpect(status().isNoContent());
    mvc.perform(
            get("/api/admin/job-grades?active=true").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.gradeCode=='G11')]").isEmpty());
    mvc.perform(get("/api/admin/job-grades").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.gradeCode=='G11')].activeFlag").value(false));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where table_name in ('JOB_GRADES','JOB_TITLES')"
                    + " and changed_by=?",
                Integer.class,
                EXEC_USER))
        .isEqualTo(4);
  }

  @Test
  void locationsAndLeaveTypes() throws Exception {
    mvc.perform(delete("/api/admin/locations/HQ").header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20602"));
    mvc.perform(
            jsonReq(
                post("/api/admin/locations"),
                exec,
                json(Map.of("locationCode", "HQ", "locationName", "Dup", "countryCode", "US"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20601"))
        .andExpect(jsonPath("$.field").value("locationCode"));
    mvc.perform(
            jsonReq(
                post("/api/admin/locations"),
                exec,
                json(
                    Map.of(
                        "locationCode",
                        "AUS",
                        "locationName",
                        "Austin",
                        "countryCode",
                        "US",
                        "timezone",
                        "America/Chicago"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.locationCode").value("AUS"))
        .andExpect(jsonPath("$.activeEmployees").value(0));
    mvc.perform(delete("/api/admin/locations/AUS").header("Authorization", "Bearer " + exec))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/admin/locations/AUS").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeFlag").value(false));

    // -20603 carryoverMax > maxBalance
    mvc.perform(
            jsonReq(
                post("/api/admin/leave-types"),
                exec,
                json(
                    Map.of(
                        "leaveTypeCode",
                        "STUDY",
                        "leaveTypeName",
                        "Study Leave",
                        "accrualFlag",
                        true,
                        "accrualRate",
                        1,
                        "accrualFrequency",
                        "MONTHLY",
                        "maxBalance",
                        5,
                        "carryoverMax",
                        10))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details[0].field").value("carryoverWithinMax"));
    // PTO has PENDING requests in the seed -> -20602
    mvc.perform(delete("/api/admin/leave-types/1").header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20602"));
    mvc.perform(get("/api/admin/leave-types/1").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.leaveTypeCode").value("PTO"))
        .andExpect(jsonPath("$.accrualRate").value("1.25"))
        .andExpect(jsonPath("$.pendingRequests").value(3));
  }

  @Test
  void systemParametersHardDeleteOnlyWhenEditable() throws Exception {
    mvc.perform(
            get("/api/admin/system-parameters?group=SECURITY")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    // -20606 non-editable (APP_VERSION)
    mvc.perform(
            jsonReq(put("/api/admin/system-parameters/1"), exec, json(Map.of("paramValue", "5.0"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20606"));
    mvc.perform(delete("/api/admin/system-parameters/1").header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20606"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from system_parameters where param_id=1", Integer.class))
        .isEqualTo(1);
    // -20603 value does not parse as dataType
    mvc.perform(
            jsonReq(
                post("/api/admin/system-parameters"),
                exec,
                json(
                    Map.of(
                        "paramGroup", "PAYROLL",
                        "paramCode", "MAX_OT_HOURS",
                        "paramValue", "lots",
                        "dataType", "NUMBER"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20603"))
        .andExpect(jsonPath("$.field").value("paramValue"));
    // -20601 duplicate (group, code)
    mvc.perform(
            jsonReq(
                post("/api/admin/system-parameters"),
                exec,
                json(
                    Map.of(
                        "paramGroup",
                        "SYSTEM",
                        "paramCode",
                        "APP_VERSION",
                        "paramValue",
                        "1",
                        "dataType",
                        "VARCHAR2"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20601"))
        .andExpect(jsonPath("$.field").value("paramCode"));
    JsonNode p =
        body(
            mvc.perform(
                    jsonReq(
                        post("/api/admin/system-parameters"),
                        exec,
                        json(
                            Map.of(
                                "paramGroup", "PAYROLL",
                                "paramCode", "MAX_OT_HOURS",
                                "paramValue", "20",
                                "dataType", "NUMBER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.editableFlag").value(true))
                .andReturn());
    int id = p.get("paramId").asInt();
    mvc.perform(
            jsonReq(
                put("/api/admin/system-parameters/" + id), exec, json(Map.of("paramValue", "abc"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20603"));
    mvc.perform(
            jsonReq(
                put("/api/admin/system-parameters/" + id), exec, json(Map.of("paramValue", "25"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paramValue").value("25"));
    mvc.perform(
            delete("/api/admin/system-parameters/" + id).header("Authorization", "Bearer " + exec))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from system_parameters where param_id=?", Integer.class, id))
        .isZero();
    mvc.perform(
            get("/api/admin/system-parameters/" + id).header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REFERENCE_NOT_FOUND"));
    assertThat(
            jdbc.queryForObject(
                "select string_agg(action_type, ',' order by audit_id) from audit_log"
                    + " where table_name='SYSTEM_PARAMETERS' and record_id=?",
                String.class,
                id))
        .isEqualTo("INSERT,UPDATE,DELETE");
  }

  @Test
  void auditLogSearch() throws Exception {
    mvc.perform(
            jsonReq(
                post("/api/admin/locations"),
                exec,
                json(Map.of("locationCode", "AUS", "locationName", "Austin", "countryCode", "US"))))
        .andExpect(status().isCreated());
    mvc.perform(
            get("/api/admin/audit-log?tableName=locations&actionType=INSERT")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].changedBy").value(EXEC_USER))
        .andExpect(
            jsonPath("$.content[0].newValues").value(org.hamcrest.Matchers.containsString("AUS")))
        .andExpect(jsonPath("$.page.totalElements").value(1));
    mvc.perform(
            get("/api/admin/audit-log?from=2024-02-01&to=2024-01-01")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(get("/api/admin/audit-log").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/admin/audit-log")
                .accept(MediaType.APPLICATION_XML)
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotAcceptable())
        .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
  }

  @Test
  void leaveJobTriggersAndLegacyAliases() throws Exception {
    // LEAVE:ADMIN required (EXECUTIVE only)
    mvc.perform(
            jsonReq(
                post("/api/admin/leave/accrual"),
                manager,
                json(Map.of("accrualDate", "2024-06-30"))))
        .andExpect(status().isForbidden());
    JsonNode job =
        body(
            mvc.perform(
                    jsonReq(
                        post("/api/admin/leave/accrual"),
                        exec,
                        json(Map.of("accrualDate", "2024-06-30"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobType").value("ACCRUAL"))
                .andExpect(jsonPath("$.startedBy").value(EXEC_USER))
                .andReturn());
    String jobId = job.get("jobId").asText();
    assertThat(job.get("status").asText()).isIn("RUNNING", "COMPLETED");
    JsonNode done = awaitJob(jobId);
    assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(done.get("finishedAt").isNull()).isFalse();
    assertThat(done.get("processed").asInt() + done.get("skipped").asInt()).isPositive();

    // -20702: pin a RUNNING carryover job for 2024 and ask again
    jdbc.update(
        "insert into leave_jobs (job_id, job_type, job_key, status, started_at, started_by)"
            + " values (gen_random_uuid(), 'CARRYOVER', '2024', 'RUNNING', current_timestamp, 'x')");
    mvc.perform(jsonReq(post("/api/admin/leave/carryover"), exec, json(Map.of("year", 2024))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20702"))
        .andExpect(jsonPath("$.field").value("year"));
    JsonNode carry =
        body(
            mvc.perform(
                    jsonReq(post("/api/admin/leave/carryover"), exec, json(Map.of("year", 2023))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobType").value("CARRYOVER"))
                .andReturn());
    assertThat(awaitJob(carry.get("jobId").asText()).get("status").asText())
        .isIn("COMPLETED", "FAILED");
    mvc.perform(
            get("/api/admin/leave/jobs/00000000-0000-0000-0000-000000000000")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    mvc.perform(jsonReq(post("/api/admin/leave/carryover"), exec, "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mvc.perform(post("/api/leave/admin/accrual/run").header("Authorization", "Bearer " + exec))
        .andExpect(status().isMovedPermanently())
        .andExpect(header().string("Location", "/api/admin/leave/accrual"));
    mvc.perform(post("/api/leave/admin/carryover/run").header("Authorization", "Bearer " + exec))
        .andExpect(status().isMovedPermanently())
        .andExpect(header().string("Location", "/api/admin/leave/carryover"));
  }

  private JsonNode awaitJob(String jobId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (true) {
      JsonNode j =
          body(
              mvc.perform(
                      get("/api/admin/leave/jobs/" + jobId)
                          .header("Authorization", "Bearer " + exec))
                  .andExpect(status().isOk())
                  .andReturn());
      if (!"RUNNING".equals(j.get("status").asText()) || System.nanoTime() > deadline) {
        return j;
      }
      Thread.sleep(100);
    }
  }

  private static MockHttpServletRequestBuilder jsonReq(
      MockHttpServletRequestBuilder b, String token, String body) {
    return b.header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private String json(Map<String, ?> m) throws Exception {
    return json.writeValueAsString(m);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token(String email) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    return body(r).get("accessToken").asText();
  }
}
