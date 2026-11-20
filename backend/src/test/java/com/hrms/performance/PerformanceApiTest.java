package com.hrms.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.auth.ApiTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Performance fixtures: 37 (grade 2) reports to 31 (grade 6, PERFORMANCE:VIEW); 36 (grade 3) is a peer
 * with no performance permission; 21 (grade 6) is an HR-style viewer outside the review; emp 1 holds
 * PERFORMANCE:ADMIN (grade >= 8).
 */
class PerformanceApiTest extends ApiTestSupport {

    static final String BASE = "/api/v1/performance";

    String employee;
    String manager;
    String peer;
    String viewer;
    String admin;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(Instant.parse("2026-03-02T09:00:00Z"));
        jdbc.update("DELETE FROM hrms.performance_goals");
        jdbc.update("DELETE FROM hrms.performance_reviews");
        jdbc.update("DELETE FROM hrms.review_cycles");
        jdbc.update("DELETE FROM hrms.notification_queue");
        jdbc.update("DELETE FROM hrms.audit_log WHERE table_name IN "
                + "('REVIEW_CYCLES', 'PERFORMANCE_REVIEWS', 'PERFORMANCE_GOALS')");
        employee = tokenFor(37);
        manager = tokenFor(31);
        peer = tokenFor(36);
        viewer = tokenFor(21);
        admin = tokenFor(1);
    }

    ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token)));
    }

    ResultActions postJson(String token, String path, Object body) throws Exception {
        return as(token, post(BASE + path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    ResultActions expect(ResultActions r, int status, String code, Integer legacy) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
        if (legacy != null) {
            r.andExpect(jsonPath("$.legacyCode").value(legacy));
        }
        return r;
    }

    long createCycle(String name) throws Exception {
        return read(postJson(admin, "/cycles", Map.of("cycleName", name, "cycleYear", 2026,
                "startDate", "2026-01-01", "endDate", "2026-12-31")).andExpect(status().isCreated()))
                .get("cycleId").asLong();
    }

    long openCycleWithReviews() throws Exception {
        long cycle = createCycle("FY2026 Annual");
        postJson(admin, "/cycles/" + cycle + "/open", Map.of()).andExpect(status().isOk());
        postJson(admin, "/cycles/" + cycle + "/reviews/generate", Map.of()).andExpect(status().isOk());
        return cycle;
    }

    long reviewOf(long cycle, long empId) {
        return jdbc.queryForObject("SELECT review_id FROM hrms.performance_reviews WHERE cycle_id = ? AND emp_id = ?",
                Long.class, cycle, empId);
    }

    @Test
    void cycleLifecycleIsDraftOpenClosed() throws Exception {
        postJson(admin, "/cycles", Map.of("cycleName", "FY2026", "cycleYear", 2026, "startDate", "2026-01-01",
                "endDate", "2026-12-31", "selfReviewDue", "2026-11-15")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.selfReviewDue").value("2026-11-15"))
                .andExpect(jsonPath("$.createdBy").value("james.richardson@company.com"));
        long cycle = jdbc.queryForObject("SELECT cycle_id FROM hrms.review_cycles", Long.class);
        expect(postJson(admin, "/cycles/" + cycle + "/close", Map.of()), 409, "INVALID_CYCLE_STATUS", -20401);
        postJson(admin, "/cycles/" + cycle + "/open", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.modifiedBy").value("james.richardson@company.com"));
        expect(postJson(admin, "/cycles/" + cycle + "/open", Map.of()), 409, "INVALID_CYCLE_STATUS", -20401);
        postJson(admin, "/cycles/" + cycle + "/close", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        expect(postJson(admin, "/cycles/" + cycle + "/close", Map.of()), 409, "INVALID_CYCLE_STATUS", -20401);
        expect(postJson(admin, "/cycles/" + cycle + "/open", Map.of()), 409, "INVALID_CYCLE_STATUS", -20401);
        assertThat(jdbc.queryForList("SELECT action_type || ':' || COALESCE(old_values->>'status', '-') || '>' "
                + "|| (new_values->>'status') FROM hrms.audit_log WHERE table_name = 'REVIEW_CYCLES' "
                + "ORDER BY audit_id", String.class)).containsExactly("INSERT:->DRAFT", "STATUS_CHANGE:DRAFT>OPEN",
                "STATUS_CHANGE:OPEN>CLOSED");
        // legacy statuses that no code path sets still close
        long legacy = createCycle("Legacy");
        jdbc.update("UPDATE hrms.review_cycles SET status = 'CALIBRATION' WHERE cycle_id = ?", legacy);
        postJson(admin, "/cycles/" + legacy + "/close", Map.of()).andExpect(status().isOk());
        expect(postJson(admin, "/cycles/999999/open", Map.of()), 404, "NOT_FOUND", null);
    }

    @Test
    void cycleListDefaultsToOpenAndDraftNewestYearFirst() throws Exception { // FRM-070
        long old = createCycle("FY2025");
        jdbc.update("UPDATE hrms.review_cycles SET cycle_year = 2025 WHERE cycle_id = ?", old);
        long draft = createCycle("FY2026");
        long closed = createCycle("FY2024");
        jdbc.update("UPDATE hrms.review_cycles SET status = 'CLOSED', cycle_year = 2024 WHERE cycle_id = ?", closed);
        as(employee, get(BASE + "/cycles")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cycleId").value(draft)).andExpect(jsonPath("$[1].cycleId").value(old));
        as(employee, get(BASE + "/cycles").param("includeClosed", "true"))
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[2].cycleId").value(closed));
        as(employee, get(BASE + "/cycles/" + closed)).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void cyclesAreReadOnlyExceptAdminLifecycleCommands() throws Exception { // FRM-071
        long cycle = createCycle("FY2026");
        expect(postJson(manager, "/cycles", Map.of("cycleName", "x", "cycleYear", 2026, "startDate", "2026-01-01",
                "endDate", "2026-12-31")), 403, "FORBIDDEN", null);
        expect(postJson(viewer, "/cycles/" + cycle + "/open", Map.of()), 403, "FORBIDDEN", null);
        expect(postJson(manager, "/cycles/" + cycle + "/close", Map.of()), 403, "FORBIDDEN", null);
        expect(postJson(manager, "/cycles/" + cycle + "/reviews/generate", Map.of()), 403, "FORBIDDEN", null);
        expect(as(admin, delete(BASE + "/cycles/" + cycle)), 405, "DELETE_FORBIDDEN", -20504);
        expect(postJson(admin, "/cycles", Map.of("cycleName", "x", "cycleYear", 2026, "startDate", "2026-02-01",
                "endDate", "2026-01-31")), 400, "VALIDATION_FAILED", null);
        expect(postJson(admin, "/cycles", Map.of("cycleYear", 2026, "startDate", "2026-01-01",
                "endDate", "2026-12-31")), 400, "VALIDATION_FAILED", null);
        mvc.perform(get(BASE + "/cycles")).andExpect(status().isUnauthorized());
    }

    @Test
    void generateCreatesOneReviewPerActiveEmployeeWithAManagerOnce() throws Exception {
        long cycle = createCycle("FY2026");
        int expected = jdbc.queryForObject("SELECT count(*) FROM hrms.employees "
                + "WHERE employment_status = 'ACTIVE' AND manager_emp_id IS NOT NULL", Integer.class);
        postJson(admin, "/cycles/" + cycle + "/reviews/generate", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewsCreated").value(expected))
                .andExpect(jsonPath("$.employeesSkipped").value(0));
        postJson(admin, "/cycles/" + cycle + "/reviews/generate", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewsCreated").value(0))
                .andExpect(jsonPath("$.employeesSkipped").value(expected));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.performance_reviews", Integer.class))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.performance_reviews WHERE emp_id IN (1, 99)",
                Integer.class)).isZero(); // no manager / terminated
        Map<String, Object> r = jdbc.queryForMap("SELECT reviewer_emp_id, status, review_type "
                + "FROM hrms.performance_reviews WHERE emp_id = 37");
        assertThat(r).containsEntry("reviewer_emp_id", 31L).containsEntry("status", "NOT_STARTED")
                .containsEntry("review_type", "ANNUAL");
        assertThat(jdbc.queryForObject("SELECT subject FROM hrms.notification_queue WHERE recipient_emp_id = 37",
                String.class)).isEqualTo("Performance Review Initiated");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.audit_log WHERE table_name = 'PERFORMANCE_REVIEWS' "
                + "AND action_type = 'INSERT'", Integer.class)).isEqualTo(expected);
    }

    @Test
    void reviewWorkflowEnforcesTheStatusMachineAndParties() throws Exception { // PKG-F02, DEF-21, OQ-09
        long id = reviewOf(openCycleWithReviews(), 37);
        String r = "/reviews/" + id;
        // DEF-21: the manager review used to complete a review in any status
        expect(postJson(manager, r + "/manager-review", Map.of("overallRating", 4.0)), 409,
                "INVALID_REVIEW_STATUS", -20402);
        // DEF-21: acknowledge used to be a silent no-op
        expect(postJson(employee, r + "/acknowledge", Map.of()), 409, "INVALID_REVIEW_STATUS", -20402);
        expect(postJson(peer, r + "/self-assessment", Map.of("selfAssessment", "x")), 404, "NOT_FOUND", null);
        expect(postJson(manager, r + "/self-assessment", Map.of("selfAssessment", "x")), 403, "FORBIDDEN", null);
        expect(postJson(viewer, r + "/self-assessment", Map.of("selfAssessment", "x")), 403, "FORBIDDEN", null);
        expect(postJson(employee, r + "/self-assessment", Map.of()), 400, "VALIDATION_FAILED", null);
        postJson(employee, r + "/self-assessment", Map.of("selfAssessment", "Shipped the API")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MANAGER_REVIEW"))
                .andExpect(jsonPath("$.selfAssessment").value("Shipped the API"));
        expect(postJson(employee, r + "/self-assessment", Map.of("selfAssessment", "again")), 409,
                "INVALID_REVIEW_STATUS", -20402);
        assertThat(jdbc.queryForObject("SELECT subject FROM hrms.notification_queue WHERE recipient_emp_id = 31 "
                + "AND reference_id = ?", String.class, id))
                .isEqualTo("Self-Assessment Submitted - Ready for Manager Review");

        expect(postJson(employee, r + "/manager-review", Map.of("overallRating", 5.0)), 403, "FORBIDDEN", null);
        expect(postJson(manager, r + "/manager-review", Map.of("overallRating", 5.1)), 400, "INVALID_RATING", -20403);
        expect(postJson(manager, r + "/manager-review", Map.of("overallRating", 0.9)), 400, "INVALID_RATING", -20403);
        expect(postJson(manager, r + "/manager-review", Map.of("overallRating", 4.25)), 400, "INVALID_RATING", -20403);
        expect(postJson(manager, r + "/manager-review", Map.of()), 400, "VALIDATION_FAILED", null);
        postJson(manager, r + "/manager-review", Map.of("overallRating", 4.5, "managerAssessment", "Great",
                "strengths", "Delivery", "areasForImprovement", "Docs", "developmentPlan", "Mentor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.overallRating").value(4.5))
                .andExpect(jsonPath("$.ratingLabel").value("Exceptional"))
                .andExpect(jsonPath("$.areasForImprovement").value("Docs"))
                .andExpect(jsonPath("$.reviewerName").value("KEVIN GARCIA"))
                .andExpect(jsonPath("$.employeeName").value("ALEX TAYLOR"));
        expect(postJson(manager, r + "/manager-review", Map.of("overallRating", 3.0)), 409,
                "INVALID_REVIEW_STATUS", -20402);
        assertThat(jdbc.queryForObject("SELECT subject FROM hrms.notification_queue WHERE recipient_emp_id = 37 "
                + "AND reference_id = ? AND subject <> 'Performance Review Initiated'", String.class, id))
                .isEqualTo("Performance Review Completed");

        expect(postJson(manager, r + "/acknowledge", Map.of()), 403, "FORBIDDEN", null);
        postJson(employee, r + "/acknowledge", Map.of("comments", "Thanks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.employeeComments").value("Thanks"))
                .andExpect(jsonPath("$.employeeAckDate").isNotEmpty());
        expect(postJson(employee, r + "/acknowledge", Map.of()), 409, "INVALID_REVIEW_STATUS", -20402);
        assertThat(jdbc.queryForList("SELECT (old_values->>'status') || '>' || (new_values->>'status') "
                + "FROM hrms.audit_log WHERE table_name = 'PERFORMANCE_REVIEWS' AND action_type = 'STATUS_CHANGE' "
                + "AND record_id = ? ORDER BY audit_id", String.class, id)).containsExactly(
                "NOT_STARTED>MANAGER_REVIEW", "MANAGER_REVIEW>COMPLETED", "COMPLETED>ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("SELECT new_values->>'ratingLabel' FROM hrms.audit_log WHERE "
                + "record_id = ? AND new_values->>'status' = 'COMPLETED'", String.class, id)).isEqualTo("Exceptional");
        expect(as(admin, delete(BASE + r)), 405, "DELETE_FORBIDDEN", -20504);
    }

    @Test
    void ratingLabelsFollowTheLegacyBands() {
        Map.of("5.0", "Exceptional", "4.5", "Exceptional", "4.4", "Exceeds Expectations",
                "3.5", "Exceeds Expectations", "3.4", "Meets Expectations", "2.5", "Meets Expectations",
                "2.4", "Needs Improvement", "1.5", "Needs Improvement", "1.4", "Unsatisfactory",
                "1.0", "Unsatisfactory").forEach((rating, label) ->
                assertThat(PerformanceService.ratingLabel(new BigDecimal(rating))).as(rating).isEqualTo(label));
    }

    @Test
    void reviewsAreReadByTheirPartiesAndPerformanceViewers() throws Exception { // FRM-076, FRM-077, OQ-09
        long cycle = openCycleWithReviews();
        long id = reviewOf(cycle, 37);
        as(employee, get(BASE + "/reviews/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeName").value("ALEX TAYLOR"))
                .andExpect(jsonPath("$.jobTitle").value("Junior Developer"))
                .andExpect(jsonPath("$.cycleName").value("FY2026 Annual"));
        as(manager, get(BASE + "/reviews/" + id)).andExpect(status().isOk());
        as(viewer, get(BASE + "/reviews/" + id)).andExpect(status().isOk());
        expect(as(peer, get(BASE + "/reviews/" + id)), 404, "NOT_FOUND", null);
        expect(as(peer, get(BASE + "/reviews/" + id + "/goals")), 404, "NOT_FOUND", null);
        as(employee, get(BASE + "/reviews")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewId").value(id));
        as(manager, get(BASE + "/reviews").param("role", "REVIEWER").param("cycleId", String.valueOf(cycle)))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.reviewId == " + id + ")]").exists());
        as(manager, get(BASE + "/reviews").param("role", "REVIEWER").param("cycleId", "999999"))
                .andExpect(jsonPath("$.length()").value(0));
        expect(as(peer, get(BASE + "/cycles/" + cycle + "/reviews")), 403, "FORBIDDEN", null);
        as(viewer, get(BASE + "/cycles/" + cycle + "/reviews").param("status", "NOT_STARTED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("NOT_STARTED"));
        expect(as(viewer, get(BASE + "/cycles/999999/reviews")), 404, "NOT_FOUND", null);
    }

    @Test
    void ratingDistributionCountsRatedReviewsPerLabel() throws Exception {
        long cycle = openCycleWithReviews();
        jdbc.update("UPDATE hrms.performance_reviews SET overall_rating = 4.6, rating_label = 'Exceptional', "
                + "status = 'COMPLETED' WHERE cycle_id = ? AND emp_id IN (32, 33)", cycle);
        jdbc.update("UPDATE hrms.performance_reviews SET overall_rating = 3.0, rating_label = 'Meets Expectations', "
                + "status = 'COMPLETED' WHERE cycle_id = ? AND emp_id IN (2, 37)", cycle);
        as(viewer, get(BASE + "/cycles/" + cycle + "/rating-distribution")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].ratingLabel").value("Exceptional"))
                .andExpect(jsonPath("$[0].reviewCount").value(2)).andExpect(jsonPath("$[0].percentage").value(50.0));
        as(viewer, get(BASE + "/cycles/" + cycle + "/rating-distribution").param("deptId", "31"))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].percentage").value(33.3));
        expect(as(employee, get(BASE + "/cycles/" + cycle + "/rating-distribution")), 403, "FORBIDDEN", null);
    }

    @Test
    void goalsAreAddedAndProgressedByThePartiesButNeverDeleted() throws Exception { // FRM-074/075, PKG-F03
        long id = reviewOf(openCycleWithReviews(), 37);
        String goals = "/reviews/" + id + "/goals";
        long goal = read(postJson(employee, goals, Map.of("goalTitle", "Ship P4", "weightPct", 40,
                "targetDate", "2026-06-30")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.empId").value(37)).andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.goalCategory").value("BUSINESS"))
                .andExpect(jsonPath("$.progressPct").value(0))).get("goalId").asLong();
        postJson(manager, goals, Map.of("goalTitle", "Mentor", "goalCategory", "LEADERSHIP"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.empId").value(37))
                .andExpect(jsonPath("$.weightPct").value(0));
        expect(postJson(peer, goals, Map.of("goalTitle", "x")), 404, "NOT_FOUND", null);
        expect(postJson(viewer, goals, Map.of("goalTitle", "x")), 403, "FORBIDDEN", null);
        expect(postJson(employee, goals, Map.of("goalTitle", "x", "weightPct", 101)), 400, "VALIDATION_FAILED", null);
        expect(postJson(employee, goals, Map.of("goalTitle", "x", "goalCategory", "FUN")), 400,
                "VALIDATION_FAILED", null);
        as(viewer, get(BASE + goals)).andExpect(jsonPath("$.length()").value(2));

        String progress = "/goals/" + goal + "/progress";
        postJson(employee, progress, Map.of("progressPct", 50, "comments", "halfway")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS")).andExpect(jsonPath("$.comments").value("halfway"));
        postJson(manager, progress, Map.of("progressPct", 100)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.comments").value("halfway"));
        postJson(employee, progress, Map.of("progressPct", 0)).andExpect(jsonPath("$.status").value("COMPLETED"));
        postJson(employee, progress, Map.of("progressPct", 20, "status", "DEFERRED"))
                .andExpect(jsonPath("$.status").value("DEFERRED"));
        // DEF-21: progress was never range-checked
        expect(postJson(employee, progress, Map.of("progressPct", 101)), 400, "VALIDATION_FAILED", null);
        expect(postJson(employee, progress, Map.of("progressPct", -1)), 400, "VALIDATION_FAILED", null);
        expect(postJson(employee, progress, Map.of()), 400, "VALIDATION_FAILED", null);
        expect(postJson(peer, progress, Map.of("progressPct", 10)), 404, "NOT_FOUND", null);
        expect(postJson(viewer, progress, Map.of("progressPct", 10)), 403, "FORBIDDEN", null);
        expect(as(peer, get(BASE + "/goals/" + goal)), 404, "NOT_FOUND", null);
        as(employee, get(BASE + "/goals/" + goal)).andExpect(jsonPath("$.progressPct").value(20));
        expect(as(employee, delete(BASE + "/goals/" + goal)), 405, "DELETE_FORBIDDEN", -20504);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.performance_goals", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT action_type || ':' || (new_values->>'status') FROM hrms.audit_log "
                + "WHERE table_name = 'PERFORMANCE_GOALS' AND record_id = ? ORDER BY audit_id", String.class, goal))
                .containsExactly("INSERT:NOT_STARTED", "UPDATE:IN_PROGRESS", "UPDATE:COMPLETED", "UPDATE:COMPLETED",
                        "UPDATE:DEFERRED");
        expect(postJson(employee, "/goals/999999/progress", Map.of("progressPct", 10)), 404, "NOT_FOUND", null);
    }

    @Test
    void databaseRejectsOutOfRangeProgressAndDuplicateReviews() throws Exception { // V200 backstops
        long id = reviewOf(openCycleWithReviews(), 37);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.performance_goals (review_id, emp_id, goal_title, progress_pct, created_by)
                VALUES (?, 37, 'x', 150, 'test')""", id)).hasMessageContaining("chk_goal_progress_range");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.performance_reviews (cycle_id, emp_id, reviewer_emp_id, created_by)
                SELECT cycle_id, emp_id, reviewer_emp_id, 'test' FROM hrms.performance_reviews WHERE review_id = ?
                """, id)).hasMessageContaining("uq_performance_reviews_cycle_emp_type");
    }
}
