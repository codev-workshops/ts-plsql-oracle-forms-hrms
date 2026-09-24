package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Level 1 for contracts/p1-performance: every status transition, every P1 error code, JWT scoping,
 * set-based generation, and the audit_log / notification_queue side effects, end to end against the
 * frozen seed (cycle 9001 with reviews 5001-5004; accounts of AuthApiTestBase). Seed graph used:
 * emp 2 (STAFF, manager = emp 1 EXECUTIVE with PERFORMANCE:ADMIN), emp 21 (MANAGER with
 * PERFORMANCE:VIEW, manages 22/23/24), emp 11 (STAFF, unrelated).
 */
class PerformanceApiTest extends AuthApiTestBase {

  private static final String OTHER_STAFF_EMAIL = "david.martinez@company.com"; // emp 11, user 4
  private static final int ELIGIBLE_EMPLOYEES = 22; // ACTIVE with manager_emp_id (seed)

  private String exec;
  private String manager;
  private String staff;
  private String otherStaff;

  @BeforeEach
  void seed() throws Exception {
    seedAccounts();
    jdbc.update("delete from notification_queue");
    jdbc.update(
        "delete from performance_goals where review_id in"
            + " (select review_id from performance_reviews where cycle_id <> 9001)");
    jdbc.update("delete from performance_reviews where cycle_id <> 9001");
    jdbc.update("delete from review_cycles where cycle_id <> 9001");
    jdbc.update("update review_cycles set status = 'IN_PROGRESS' where cycle_id = 9001");
    jdbc.update(
        "update performance_reviews set status = 'MANAGER_REVIEW', overall_rating = null,"
            + " rating_label = null where review_id in (5001, 5002)");
    jdbc.update("update performance_reviews set status = 'SELF_REVIEW' where review_id = 5003");
    exec = token(EXEC_EMAIL);
    manager = token(MANAGER_EMAIL);
    staff = token(STAFF_EMAIL);
    otherStaff = token(OTHER_STAFF_EMAIL);
  }

  // ------------------------------------------------------------------ cycles

  @Test
  void cycleLifecycleAuditAndStatusMachine() throws Exception {
    mvc.perform(json(post("/api/performance/cycles"), staff, cycleBody("2025 Annual Review", 2025)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    Map<String, Object> bad = cycleBody("2025 Annual Review", 2025);
    bad.put("endDate", "2024-12-31");
    mvc.perform(json(post("/api/performance/cycles"), exec, bad))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("endDate"));

    long cycleId = createCycle("2025 Annual Review", 2025);
    mvc.perform(
            get("/api/performance/cycles/" + cycleId).header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.createdBy").value("1"));
    assertThat(auditCount("REVIEW_CYCLES", cycleId, "INSERT")).isEqualTo(1);

    // close from DRAFT -> -20401
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/close")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20401"))
        .andExpect(
            jsonPath("$.message")
                .value("Cannot close cycle - must be OPEN, IN_PROGRESS or CALIBRATION"));

    // PUT in DRAFT ok
    Map<String, Object> edit = cycleBody("2025 Annual Review (edited)", 2025);
    mvc.perform(json(put("/api/performance/cycles/" + cycleId), exec, edit))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cycleName").value("2025 Annual Review (edited)"));

    // DRAFT -> OPEN
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/open")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"));
    assertThat(auditCount("REVIEW_CYCLES", cycleId, "UPDATE")).isEqualTo(2);

    // open twice -> -20401 ; PUT outside DRAFT -> -20401
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/open")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20401"))
        .andExpect(jsonPath("$.message").value("Cannot open cycle - must be in DRAFT status"));
    mvc.perform(json(put("/api/performance/cycles/" + cycleId), exec, edit))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20401"))
        .andExpect(jsonPath("$.message").value("Cannot edit cycle - must be in DRAFT status"));

    // OPEN -> CLOSED, then generate on CLOSED -> -20401
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/close")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/generate-reviews")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20401"))
        .andExpect(
            jsonPath("$.message").value("Cannot generate reviews - cycle must be DRAFT or OPEN"));

    // seed cycle 9001 is IN_PROGRESS -> CLOSED allowed
    mvc.perform(
            post("/api/performance/cycles/9001/close").header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));

    mvc.perform(get("/api/performance/cycles/424242").header("Authorization", "Bearer " + exec))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CYCLE_NOT_FOUND"));
  }

  @Test
  void listCyclesDefaultsFiltersAndValidatesParameters() throws Exception {
    long draft = createCycle("2026 Annual Review", 2026);
    // default filter OPEN,DRAFT excludes the IN_PROGRESS seed cycle
    mvc.perform(get("/api/performance/cycles").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].cycleId").value(draft));
    mvc.perform(
            get("/api/performance/cycles")
                .param("status", "IN_PROGRESS,DRAFT")
                .param("sort", "cycleYear,asc")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].cycleId").value(9001));
    mvc.perform(
            get("/api/performance/cycles")
                .param("status", "BOGUS")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("status"));
    mvc.perform(
            get("/api/performance/cycles")
                .param("sort", "cycleName,asc")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("sort"));
    mvc.perform(get("/api/performance/cycles")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------- generate (PERF-05)

  @Test
  void generateReviewsIsSetBasedIdempotentAndNotifies() throws Exception {
    long cycleId = createCycle("2027 Annual Review", 2027);
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/generate-reviews")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isForbidden());

    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/generate-reviews")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cycleId").value(cycleId))
        .andExpect(jsonPath("$.generated").value(ELIGIBLE_EMPLOYEES))
        .andExpect(jsonPath("$.skipped").value(0));

    Integer rows =
        jdbc.queryForObject(
            "select count(*) from performance_reviews where cycle_id = ? and status = 'NOT_STARTED'"
                + " and review_type = 'ANNUAL' and created_by = '1'",
            Integer.class,
            cycleId);
    assertThat(rows).isEqualTo(ELIGIBLE_EMPLOYEES);
    Integer wrongReviewer =
        jdbc.queryForObject(
            "select count(*) from performance_reviews r join employees e on e.emp_id = r.emp_id where"
                + " r.cycle_id = ? and r.reviewer_emp_id <> e.manager_emp_id",
            Integer.class,
            cycleId);
    assertThat(wrongReviewer).isZero();
    Integer terminated =
        jdbc.queryForObject(
            "select count(*) from performance_reviews where cycle_id = ? and emp_id in (1, 99)",
            Integer.class,
            cycleId);
    assertThat(terminated).isZero();
    Integer notified =
        jdbc.queryForObject(
            "select count(*) from notification_queue where reference_table = 'PERFORMANCE_REVIEWS' and"
                + " subject = 'Performance Review Initiated' and body = 'Your annual performance review"
                + " has been initiated. Please complete your self-assessment.'",
            Integer.class);
    assertThat(notified).isEqualTo(ELIGIBLE_EMPLOYEES);
    Integer audited =
        jdbc.queryForObject(
            "select count(*) from audit_log a join performance_reviews r on r.review_id = a.record_id"
                + " where a.table_name = 'PERFORMANCE_REVIEWS' and a.action_type = 'INSERT' and r.cycle_id = ?",
            Integer.class,
            cycleId);
    assertThat(audited).isEqualTo(ELIGIBLE_EMPLOYEES);

    // second run: nothing new, everything skipped, no new notifications
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/generate-reviews")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generated").value(0))
        .andExpect(jsonPath("$.skipped").value(ELIGIBLE_EMPLOYEES));
    assertThat(jdbc.queryForObject("select count(*) from notification_queue", Integer.class))
        .isEqualTo(ELIGIBLE_EMPLOYEES);

    // OPEN still allows generation
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/open")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/generate-reviews")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generated").value(0));

    // paged listing needs PERFORMANCE:VIEW / ADMIN
    mvc.perform(
            get("/api/performance/cycles/" + cycleId + "/reviews")
                .param("size", "5")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(5))
        .andExpect(jsonPath("$.totalElements").value(ELIGIBLE_EMPLOYEES))
        .andExpect(jsonPath("$.totalPages").value(5))
        .andExpect(jsonPath("$.content[0].employeeName").isString());
    mvc.perform(
            get("/api/performance/cycles/" + cycleId + "/reviews")
                .param("status", "COMPLETED")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/performance/cycles/" + cycleId + "/reviews")
                .param("size", "0")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("size"));
    mvc.perform(
            get("/api/performance/cycles/" + cycleId + "/reviews")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
  }

  // ------------------------------------------------------------------ reviews

  @Test
  void reviewStatusMachineScopingAndNotifications() throws Exception {
    long cycleId = createCycle("2028 Annual Review", 2028);
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/generate-reviews")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk());
    long reviewId = reviewOf(cycleId, 2); // reviewee emp 2 (staff), reviewer emp 1 (exec)

    // /mine is JWT-scoped: staff sees its own review, a client-supplied empId is not a thing
    mvc.perform(
            get("/api/performance/reviews/mine")
                .param("cycleId", String.valueOf(cycleId))
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].reviewId").value(reviewId))
        .andExpect(jsonPath("$[0].empId").value(2))
        .andExpect(jsonPath("$[0].reviewerName").value("JAMES RICHARDSON"));
    mvc.perform(
            get("/api/performance/reviews/mine")
                .param("cycleId", "0")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("cycleId"));

    // read scoping: reviewee, reviewer, PERFORMANCE:VIEW ok; unrelated staff forbidden
    for (String t : new String[] {staff, exec, manager}) {
      mvc.perform(
              get("/api/performance/reviews/" + reviewId).header("Authorization", "Bearer " + t))
          .andExpect(status().isOk());
    }
    mvc.perform(
            get("/api/performance/reviews/" + reviewId)
                .header("Authorization", "Bearer " + otherStaff))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            get("/api/performance/reviews/999999").header("Authorization", "Bearer " + otherStaff))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

    // manager-review before self-assessment: rating bounds first (-20403), then status (-20402)
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/manager-review"),
                exec,
                managerBody(5.5)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20403"))
        .andExpect(jsonPath("$.field").value("overallRating"))
        .andExpect(jsonPath("$.message").value("Rating must be between 1.0 and 5.0"));
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/manager-review"),
                exec,
                managerBody(0.9)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20403"));
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/manager-review"),
                exec,
                managerBody(4.55)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/manager-review"),
                exec,
                managerBody(4.0)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"))
        .andExpect(jsonPath("$.message").value("Review not found or not in correct status"));
    // manager-review by someone who is not the reviewer -> 403 (before rating/status)
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/manager-review"),
                manager,
                managerBody(9.0)))
        .andExpect(status().isForbidden());

    // self-assessment: only the reviewee; blank text is Bean Validation
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/self-assessment"),
                exec,
                Map.of("selfAssessment", "x")))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/self-assessment"),
                staff,
                Map.of("selfAssessment", "   ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/self-assessment"),
                staff,
                Map.of("selfAssessment", "Shipped P0 and P1.")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MANAGER_REVIEW"))
        .andExpect(jsonPath("$.selfAssessment").value("Shipped P0 and P1."));
    assertThat(notificationCount(1, "Self-Assessment Submitted - Ready for Manager Review"))
        .isEqualTo(1);
    assertThat(auditCount("PERFORMANCE_REVIEWS", reviewId, "STATUS_CHANGE")).isEqualTo(1);
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/self-assessment"),
                staff,
                Map.of("selfAssessment", "again")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"));

    // acknowledge before COMPLETED -> -20402
    mvc.perform(
            post("/api/performance/reviews/" + reviewId + "/acknowledge")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"));

    // manager review -> COMPLETED with the derived label
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/manager-review"),
                exec,
                managerBody(4.6)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.overallRating").value(4.6))
        .andExpect(jsonPath("$.ratingLabel").value("Exceptional"))
        .andExpect(jsonPath("$.areasForImprovement").value("Delegation"));
    assertThat(notificationCount(2, "Performance Review Completed")).isEqualTo(1);
    assertThat(auditCount("PERFORMANCE_REVIEWS", reviewId, "STATUS_CHANGE")).isEqualTo(2);
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/manager-review"),
                exec,
                managerBody(3.0)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"));

    // acknowledge: reviewer forbidden, reviewee ok (optional body), terminal afterwards
    mvc.perform(
            post("/api/performance/reviews/" + reviewId + "/acknowledge")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/acknowledge"),
                staff,
                Map.of("employeeComments", "Thanks")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
        .andExpect(jsonPath("$.employeeComments").value("Thanks"))
        .andExpect(jsonPath("$.employeeAckDate").isString());
    mvc.perform(
            post("/api/performance/reviews/" + reviewId + "/acknowledge")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"));
    assertThat(auditCount("PERFORMANCE_REVIEWS", reviewId, "STATUS_CHANGE")).isEqualTo(3);
  }

  @Test
  void seededTransitionsMeetingScheduledAndSelfReview() throws Exception {
    // 5003 (emp 23, reviewer 21) is SELF_REVIEW -> reviewee can still submit; 5001 MANAGER_REVIEW
    jdbc.update(
        "update performance_reviews set status = 'MEETING_SCHEDULED' where review_id = 5001");
    mvc.perform(
            json(post("/api/performance/reviews/5001/manager-review"), manager, managerBody(2.4)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.ratingLabel").value("Needs Improvement"));
    mvc.perform(
            json(post("/api/performance/reviews/5003/manager-review"), manager, managerBody(3.0)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"));
  }

  // -------------------------------------------------------------------- goals

  @Test
  void goalRulesAndProgressDerivation() throws Exception {
    long cycleId = createCycle("2029 Annual Review", 2029);
    mvc.perform(
            post("/api/performance/cycles/" + cycleId + "/generate-reviews")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk());
    long reviewId = reviewOf(cycleId, 2);

    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/goals"),
                otherStaff,
                Map.of("goalTitle", "Nope")))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/goals"),
                manager,
                Map.of("goalTitle", "Nope")))
        .andExpect(status().isForbidden()); // PERFORMANCE:VIEW reads, does not write
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/goals"),
                staff,
                Map.of("goalTitle", "  ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/goals"),
                staff,
                Map.of("goalTitle", "Nope", "weightPct", 150)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            json(post("/api/performance/reviews/999999/goals"), staff, Map.of("goalTitle", "Nope")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

    MvcResult created =
        mvc.perform(
                json(
                    post("/api/performance/reviews/" + reviewId + "/goals"),
                    exec,
                    Map.of("goalTitle", "Ship P1", "goalDescription", "", "weightPct", 40)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("NOT_STARTED"))
            .andExpect(jsonPath("$.goalCategory").value("BUSINESS"))
            .andExpect(jsonPath("$.progressPct").value(0))
            .andExpect(jsonPath("$.weightPct").value(40))
            .andExpect(jsonPath("$.goalDescription").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.empId").value(2))
            .andReturn();
    long goalId = body(created).get("goalId").asLong();
    assertThat(created.getResponse().getHeader("Location"))
        .isEqualTo("/api/performance/goals/" + goalId);
    assertThat(auditCount("PERFORMANCE_GOALS", goalId, "INSERT")).isEqualTo(1);

    mvc.perform(
            get("/api/performance/reviews/" + reviewId + "/goals")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(
            get("/api/performance/reviews/" + reviewId + "/goals")
                .header("Authorization", "Bearer " + otherStaff))
        .andExpect(status().isForbidden());

    // progress: 0 keeps status, >0 IN_PROGRESS, 100 COMPLETED, explicit status wins
    String progress = "/api/performance/goals/" + goalId + "/progress";
    mvc.perform(json(patch(progress), staff, Map.of("progressPct", 0)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NOT_STARTED"));
    mvc.perform(json(patch(progress), staff, Map.of("progressPct", 25.5, "comments", "on track")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.progressPct").value(25.5))
        .andExpect(jsonPath("$.comments").value("on track"));
    mvc.perform(json(patch(progress), exec, Map.of("progressPct", 50, "status", "DEFERRED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEFERRED"));
    mvc.perform(json(patch(progress), staff, Map.of("progressPct", 100)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.progressPct").value(100));
    mvc.perform(json(patch(progress), staff, Map.of("progressPct", 101)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(json(patch(progress), otherStaff, Map.of("progressPct", 10)))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(patch("/api/performance/goals/999999/progress"), staff, Map.of("progressPct", 10)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GOAL_NOT_FOUND"));
    assertThat(auditCount("PERFORMANCE_GOALS", goalId, "UPDATE")).isEqualTo(4);

    // COMPLETED review: no new goals, progress still allowed; ACKNOWLEDGED: neither
    jdbc.update(
        "update performance_reviews set status = 'COMPLETED' where review_id = ?", reviewId);
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/goals"),
                staff,
                Map.of("goalTitle", "Late")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"));
    mvc.perform(json(patch(progress), staff, Map.of("progressPct", 90)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    jdbc.update(
        "update performance_reviews set status = 'ACKNOWLEDGED' where review_id = ?", reviewId);
    mvc.perform(
            json(
                post("/api/performance/reviews/" + reviewId + "/goals"),
                staff,
                Map.of("goalTitle", "Late")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"));
    mvc.perform(json(patch(progress), staff, Map.of("progressPct", 95)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20402"));
  }

  // ---------------------------------------------------------------- dashboards

  @Test
  void teamReviewsAndRatingDistribution() throws Exception {
    mvc.perform(
            get("/api/performance/cycles/9001/team-reviews")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].employeeName").value("THOMAS BAKER"))
        .andExpect(jsonPath("$[0].jobTitle").isString())
        .andExpect(jsonPath("$[0].deptName").isString())
        .andExpect(jsonPath("$[1].empId").value(23));
    mvc.perform(
            get("/api/performance/cycles/9001/team-reviews")
                .header("Authorization", "Bearer " + otherStaff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            get("/api/performance/cycles/424242/team-reviews")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CYCLE_NOT_FOUND"));

    mvc.perform(
            get("/api/performance/cycles/9001/rating-distribution")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/performance/cycles/9001/rating-distribution")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ratingLabel").value("Exceeds Expectations"))
        .andExpect(jsonPath("$[0].count").value(1))
        .andExpect(jsonPath("$[0].percentage").value(100.0));
    mvc.perform(
            get("/api/performance/cycles/9001/rating-distribution")
                .param("deptId", "20")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            get("/api/performance/cycles/9001/rating-distribution")
                .param("deptId", "0")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("deptId"));
  }

  // ------------------------------------------------------------------ helpers

  private String token(String email) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
            .andReturn();
    return json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
    return b.header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }

  private static Map<String, Object> cycleBody(String name, int year) {
    Map<String, Object> m = new HashMap<>();
    m.put("cycleName", name);
    m.put("cycleYear", year);
    m.put("startDate", year + "-01-01");
    m.put("endDate", year + "-12-31");
    m.put("selfReviewDue", year + "-11-15");
    m.put("managerReviewDue", year + "-12-01");
    return m;
  }

  private static Map<String, Object> managerBody(double rating) {
    Map<String, Object> m = new HashMap<>();
    m.put("overallRating", rating);
    m.put("managerAssessment", "Solid year.");
    m.put("strengths", "Ownership");
    m.put("improvementAreas", "Delegation");
    return m;
  }

  private long createCycle(String name, int year) throws Exception {
    MvcResult r =
        mvc.perform(json(post("/api/performance/cycles"), exec, cycleBody(name, year)))
            .andExpect(status().isCreated())
            .andExpect(
                header()
                    .string(
                        "Location", org.hamcrest.Matchers.startsWith("/api/performance/cycles/")))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andReturn();
    return body(r).get("cycleId").asLong();
  }

  private long reviewOf(long cycleId, long empId) {
    Long id =
        jdbc.queryForObject(
            "select review_id from performance_reviews where cycle_id = ? and emp_id = ?",
            Long.class,
            cycleId,
            empId);
    assertThat(id).isNotNull();
    return id;
  }

  private int auditCount(String table, long recordId, String action) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from audit_log where table_name = ? and record_id = ? and action_type = ?",
            Integer.class,
            table,
            recordId,
            action);
    return n == null ? 0 : n;
  }

  private int notificationCount(long recipientEmpId, String subject) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from notification_queue where recipient_emp_id = ? and subject = ?",
            Integer.class,
            recipientEmpId,
            subject);
    return n == null ? 0 : n;
  }
}
