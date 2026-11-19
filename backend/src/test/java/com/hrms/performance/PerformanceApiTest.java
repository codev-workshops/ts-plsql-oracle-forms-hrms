package com.hrms.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Performance slice end to end over HTTP. Seed: Patricia Williams (10) is VP HR (grade 9, full
 * access); Thomas Baker (22, grade 4, Finance dept 20) and Lisa Wong (23) report to Jennifer Park
 * (21, grade 6); Andrew Patel (24, grade 2) is their peer; Brian Foster (99) is terminated.
 */
class PerformanceApiTest extends IntegrationTest {

    private String hr;
    private String manager;
    private String employee;
    private String peer;

    @BeforeEach
    void setUp() throws Exception {
        hr = login("patricia.williams@company.com");
        manager = login("jennifer.park@company.com");
        employee = login("thomas.baker@company.com");
        peer = login("andrew.patel@company.com");
    }

    private static Map<String, Object> cycleBody(String start, String end) {
        Map<String, Object> body = new HashMap<>();
        body.put("cycleName", "FY2027 Annual");
        body.put("startDate", start);
        body.put("endDate", end);
        body.put("selfReviewDeadline", "2027-01-15");
        body.put("managerReviewDeadline", "2027-01-31");
        return body;
    }

    private long createCycle() throws Exception {
        JsonNode c = read(callJson(post("/api/v1/performance/cycles"), hr, cycleBody("2026-12-01", "2027-02-28"))
                .andExpect(status().isCreated()));
        return c.get("cycleId").asLong();
    }

    private long createReview(long cycleId, long empId) throws Exception {
        JsonNode r = read(callJson(post("/api/v1/performance/reviews"), manager,
                Map.of("cycleId", cycleId, "empId", empId)).andExpect(status().isCreated()));
        return r.get("reviewId").asLong();
    }

    private int notifications(long empId) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_queue WHERE recipient_emp_id = ?"
                + " AND reference_table = 'PERFORMANCE_REVIEWS'", Integer.class, empId);
    }

    private static void expectError(org.springframework.test.web.servlet.ResultActions r, int http, String code)
            throws Exception {
        r.andExpect(status().is(http)).andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void cycleLifecycleDraftOpenClose() throws Exception {
        expectError(callJson(post("/api/v1/performance/cycles"), employee, cycleBody("2026-12-01", "2027-02-28")),
                403, "-20304");
        expectError(callJson(post("/api/v1/performance/cycles"), hr, cycleBody("2027-03-01", "2027-02-28")),
                400, "-20000");
        expectError(callJson(post("/api/v1/performance/cycles"), hr, Map.of("cycleName", "x")), 400, "-20000");

        JsonNode created = read(callJson(post("/api/v1/performance/cycles"), hr,
                cycleBody("2026-12-01", "2027-02-28")).andExpect(status().isCreated()));
        long id = created.get("cycleId").asLong();
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("cycleYear").asInt()).isEqualTo(2026);
        assertThat(created.get("selfReviewDeadline").asText()).isEqualTo("2027-01-15");

        expectError(call(post("/api/v1/performance/cycles/" + id + "/open"), manager), 403, "-20304");
        call(post("/api/v1/performance/cycles/" + id + "/open"), hr)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        expectError(call(post("/api/v1/performance/cycles/" + id + "/open"), hr), 409, "-20401");
        call(post("/api/v1/performance/cycles/" + id + "/close"), hr)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        expectError(call(post("/api/v1/performance/cycles/" + id + "/open"), hr), 409, "-20401");
        expectError(call(post("/api/v1/performance/cycles/" + id + "/generate-reviews"), hr), 409, "-20991");
        expectError(call(post("/api/v1/performance/cycles/999999/close"), hr), 404, "-20990");

        JsonNode closed = read(call(get("/api/v1/performance/cycles?status=CLOSED"), employee)
                .andExpect(status().isOk()));
        assertThat(closed.findValues("cycleId")).extracting(JsonNode::asLong).contains(id);
        assertThat(read(call(get("/api/v1/performance/cycles?status=DRAFT"), employee)).findValues("cycleId"))
                .extracting(JsonNode::asLong).doesNotContain(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE table_name = 'REVIEW_CYCLES'"
                + " AND record_id = ?", Integer.class, id)).isEqualTo(3);
    }

    @Test
    void generateReviewsIsIdempotent() throws Exception {
        long cycleId = createCycle();
        int expected = jdbc.queryForObject("SELECT count(*) FROM employees WHERE employment_status = 'ACTIVE'"
                + " AND manager_emp_id IS NOT NULL", Integer.class);
        int bakerBefore = notifications(22);

        expectError(call(post("/api/v1/performance/cycles/" + cycleId + "/generate-reviews"), manager),
                403, "-20304");
        call(post("/api/v1/performance/cycles/" + cycleId + "/generate-reviews"), hr).andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("GENERATE_REVIEWS"))
                .andExpect(jsonPath("$.rowsAffected").value(expected));
        call(post("/api/v1/performance/cycles/" + cycleId + "/generate-reviews"), hr).andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsAffected").value(0));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM performance_reviews WHERE cycle_id = ?", Integer.class,
                cycleId)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT reviewer_emp_id FROM performance_reviews WHERE cycle_id = ?"
                + " AND emp_id = 22", Long.class, cycleId)).isEqualTo(21L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM performance_reviews WHERE cycle_id = ?"
                + " AND emp_id IN (1, 99)", Integer.class, cycleId)).isZero();
        assertThat(notifications(22)).isEqualTo(bakerBefore + 1);
    }

    @Test
    void reviewWorkflowSelfManagerAcknowledge() throws Exception {
        long cycleId = createCycle();
        call(post("/api/v1/performance/cycles/" + cycleId + "/generate-reviews"), hr).andExpect(status().isOk());
        JsonNode mine = read(call(get("/api/v1/performance/reviews?cycleId=" + cycleId), employee)
                .andExpect(status().isOk()));
        assertThat(mine).hasSize(1);
        long id = mine.get(0).get("reviewId").asLong();
        assertThat(mine.get(0).get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(mine.get(0).get("reviewerName").asText()).isEqualTo("JENNIFER PARK");

        JsonNode team = read(call(get("/api/v1/performance/reviews?scope=team&cycleId=" + cycleId), manager));
        assertThat(team.findValues("empId")).extracting(JsonNode::asLong).containsExactlyInAnyOrder(22L, 23L, 24L);
        String base = "/api/v1/performance/reviews/" + id;

        // §9 fixes: no manager review before self-assessment, no silent acknowledge
        expectError(callJson(post(base + "/manager-review"), manager, Map.of("overallRating", 4)), 409, "-20402");
        expectError(call(post(base + "/acknowledge"), employee), 409, "-20402");

        expectError(callJson(post(base + "/self-assessment"), manager, Map.of("selfAssessment", "x")),
                403, "-20304");
        expectError(callJson(post(base + "/self-assessment"), employee, Map.of("selfAssessment", "")),
                400, "-20000");
        expectError(callJson(post(base + "/self-assessment"), employee,
                Map.of("selfAssessment", "Closed the books", "selfRating", 6)), 422, "-20403");
        int managerNotes = notifications(21);
        JsonNode self = read(callJson(post(base + "/self-assessment"), employee,
                Map.of("selfAssessment", "Closed the books", "selfRating", 4)).andExpect(status().isOk()));
        assertThat(self.get("status").asText()).isEqualTo("MANAGER_REVIEW");
        assertThat(self.get("selfRating").decimalValue()).isEqualByComparingTo("4.0");
        assertThat(notifications(21)).isEqualTo(managerNotes + 1);
        expectError(callJson(post(base + "/self-assessment"), employee, Map.of("selfAssessment", "again")),
                409, "-20402");

        expectError(callJson(post(base + "/manager-review"), employee, Map.of("overallRating", 5)), 403, "-20304");
        expectError(callJson(post(base + "/manager-review"), manager, Map.of("overallRating", 5.5)), 422, "-20403");
        expectError(callJson(post(base + "/manager-review"), manager, Map.of("overallRating", 0.9)), 422, "-20403");
        int bakerNotes = notifications(22);
        JsonNode done = read(callJson(post(base + "/manager-review"), manager, Map.of("overallRating", 3.7,
                "managerComments", "Solid year", "strengths", "Accuracy", "improvements", "Delegation"))
                .andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("overallRating").decimalValue()).isEqualByComparingTo("3.7");
        assertThat(done.get("ratingLabel").asText()).isEqualTo("Exceeds Expectations");
        assertThat(done.get("managerComments").asText()).isEqualTo("Solid year");
        assertThat(notifications(22)).isEqualTo(bakerNotes + 1);
        expectError(callJson(post(base + "/manager-review"), manager, Map.of("overallRating", 4)), 409, "-20402");

        expectError(call(post(base + "/acknowledge"), manager), 403, "-20304");
        call(post(base + "/acknowledge"), employee).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        expectError(call(post(base + "/acknowledge"), employee), 409, "-20402");
        assertThat(jdbc.queryForObject("SELECT employee_ack_date IS NOT NULL FROM performance_reviews"
                + " WHERE review_id = ?", Boolean.class, id)).isTrue();

        JsonNode dist = read(call(get("/api/v1/performance/cycles/" + cycleId + "/rating-distribution"), manager)
                .andExpect(status().isOk()));
        assertThat(dist).hasSize(1);
        assertThat(dist.get(0).get("ratingLabel").asText()).isEqualTo("Exceeds Expectations");
        assertThat(dist.get(0).get("count").asInt()).isEqualTo(1);
        assertThat(read(call(get("/api/v1/performance/cycles/" + cycleId + "/rating-distribution?deptId=20"), hr)))
                .hasSize(1);
        assertThat(read(call(get("/api/v1/performance/cycles/" + cycleId + "/rating-distribution?deptId=40"), hr)))
                .isEmpty();
        expectError(call(get("/api/v1/performance/cycles/" + cycleId + "/rating-distribution"), employee),
                403, "-20304");
    }

    @Test
    void createReviewByManagerWithGuards() throws Exception {
        long cycleId = createCycle();
        int lisaNotes = notifications(23);
        JsonNode r = read(callJson(post("/api/v1/performance/reviews"), manager,
                Map.of("cycleId", cycleId, "empId", 23)).andExpect(status().isCreated()));
        assertThat(r.get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(r.get("reviewerEmpId").asLong()).isEqualTo(21);
        assertThat(r.get("employeeName").asText()).isEqualTo("LISA WONG");
        assertThat(notifications(23)).isEqualTo(lisaNotes + 1);

        expectError(callJson(post("/api/v1/performance/reviews"), manager, Map.of("cycleId", cycleId, "empId", 23)),
                409, "-20991");
        expectError(callJson(post("/api/v1/performance/reviews"), employee, Map.of("cycleId", cycleId, "empId", 24)),
                403, "-20304");
        expectError(callJson(post("/api/v1/performance/reviews"), hr, Map.of("cycleId", cycleId, "empId", 424242)),
                404, "-20001");
        expectError(callJson(post("/api/v1/performance/reviews"), hr, Map.of("cycleId", cycleId, "empId", 99)),
                409, "-20012");
        expectError(callJson(post("/api/v1/performance/reviews"), hr, Map.of("cycleId", 999999, "empId", 23)),
                404, "-20990");
        expectError(callJson(post("/api/v1/performance/reviews"), hr,
                Map.of("cycleId", cycleId, "empId", 24, "reviewerEmpId", 24)), 400, "-20000");
        JsonNode byHr = read(callJson(post("/api/v1/performance/reviews"), hr,
                Map.of("cycleId", cycleId, "empId", 24)).andExpect(status().isCreated()));
        assertThat(byHr.get("reviewerEmpId").asLong()).isEqualTo(21);
    }

    @Test
    void goalsDeriveStatusFromProgress() throws Exception {
        long reviewId = createReview(createCycle(), 22);
        String goals = "/api/v1/performance/reviews/" + reviewId + "/goals";

        expectError(callJson(post(goals), peer, Map.of("goalTitle", "Sneaky")), 403, "-20304");
        expectError(callJson(post(goals), employee, Map.of("goalTitle", " ")), 400, "-20000");
        JsonNode g = read(callJson(post(goals), employee, Map.of("goalTitle", "Automate month-end close",
                "weightPct", 40, "targetDate", "2027-06-30")).andExpect(status().isCreated()));
        assertThat(g.get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(g.get("progressPct").decimalValue()).isEqualByComparingTo("0");
        String progress = "/api/v1/performance/goals/" + g.get("goalId").asLong() + "/progress";

        expectError(callJson(post(progress), peer, Map.of("progressPct", 10)), 403, "-20304");
        expectError(callJson(post(progress), employee, Map.of("progressPct", 150)), 400, "-20000");
        expectError(callJson(post(progress), employee, Map.of("progressPct", 10, "status", "BOGUS")), 400, "-20000");
        callJson(post(progress), employee, Map.of("progressPct", 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));
        callJson(post(progress), employee, Map.of("progressPct", 40)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        callJson(post(progress), manager, Map.of("progressPct", 50, "status", "DEFERRED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DEFERRED"));
        callJson(post(progress), employee, Map.of("progressPct", 100)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progressPct").value(100));
        expectError(callJson(post("/api/v1/performance/goals/999999/progress"), employee, Map.of("progressPct", 1)),
                404, "-20990");

        JsonNode list = read(call(get(goals), manager).andExpect(status().isOk()));
        assertThat(list).hasSize(1);
        JsonNode review = read(call(get("/api/v1/performance/reviews/" + reviewId), employee)
                .andExpect(status().isOk()));
        assertThat(review.get("goals").get(0).get("goalTitle").asText()).isEqualTo("Automate month-end close");
        expectError(call(get("/api/v1/performance/reviews/" + reviewId), peer), 403, "-20304");
        expectError(call(get(goals), peer), 403, "-20304");
        call(get("/api/v1/performance/reviews/" + reviewId), hr).andExpect(status().isOk());
    }

    @Test
    void lookupErrors() throws Exception {
        expectError(call(get("/api/v1/performance/reviews/999999"), hr), 404, "-20990");
        expectError(call(get("/api/v1/performance/reviews?scope=everyone"), hr), 400, "-20000");
        expectError(call(get("/api/v1/performance/cycles?status=NOPE"), hr), 400, "-20000");
        call(get("/api/v1/performance/cycles"), null).andExpect(status().isUnauthorized());
    }

    @Test
    void ratingLabelBoundaries() {
        assertThat(PerformanceService.ratingLabel(new java.math.BigDecimal("5.0"))).isEqualTo("Exceptional");
        assertThat(PerformanceService.ratingLabel(new java.math.BigDecimal("4.5"))).isEqualTo("Exceptional");
        assertThat(PerformanceService.ratingLabel(new java.math.BigDecimal("4.4"))).isEqualTo("Exceeds Expectations");
        assertThat(PerformanceService.ratingLabel(new java.math.BigDecimal("3.5"))).isEqualTo("Exceeds Expectations");
        assertThat(PerformanceService.ratingLabel(new java.math.BigDecimal("2.5"))).isEqualTo("Meets Expectations");
        assertThat(PerformanceService.ratingLabel(new java.math.BigDecimal("1.5"))).isEqualTo("Needs Improvement");
        assertThat(PerformanceService.ratingLabel(new java.math.BigDecimal("1.4"))).isEqualTo("Unsatisfactory");
    }
}
