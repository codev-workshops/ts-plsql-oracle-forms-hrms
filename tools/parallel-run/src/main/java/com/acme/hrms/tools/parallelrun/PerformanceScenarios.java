package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.LegacyCall;
import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 Level-2 scenarios (TEST_STRATEGY.md §5 row 1, COMPONENT_MAPPING.md §6): PKG_PERFORMANCE
 * against {@code /api/performance/*}. Legacy expectations are recorded from the PL/SQL reference
 * (golden-oracle mode OFF, {@code legacy_source=recorded}); the integration session replays the
 * legacy leg when Oracle is attached.
 *
 * <p>Fixed ids come from tools/fixtures/pg/03_transaction_data.sql (cycle 9001 IN_PROGRESS). The
 * seeded reviews belong to employees without a login (04_user_accounts.sql), so review scenarios
 * generate a fresh cycle and act on the review of emp 2 (reviewer = manager emp 1) created in
 * {@code setup}; captured ids are referenced as {@code {cycleId}} / {@code {reviewId}} / {@code
 * {goalId}} (see {@link RestRunner}).
 */
final class PerformanceScenarios {

  private PerformanceScenarios() {}

  /** emp 1, EXECUTIVE (PERFORMANCE:ADMIN), manager of emp 2. */
  static final String ADMIN = "james.richardson@company.com";

  /** emp 2, STAFF, reviewee of the review generated for a fresh cycle. */
  static final String EMP_2 = ScenarioRegistry.USER;

  /**
   * ACTIVE employees with a manager in the frozen seed = rows generate_reviews_for_cycle inserts.
   */
  static final String ELIGIBLE_EMPLOYEES = "22";

  private static final Map<String, Object> NEW_CYCLE =
      Map.of(
          "cycleName", "Parallel-run cycle",
          "cycleYear", 2030,
          "startDate", "2030-01-01",
          "endDate", "2030-12-31");

  private static final String CREATE_CYCLE_PLSQL =
      "declare v_id number; begin v_id := pkg_performance.create_review_cycle("
          + "'Parallel-run cycle', 2030, date '2030-01-01', date '2030-12-31', null, null, :user); "
          + ":cycle_id := v_id; end;";

  static List<Scenario> all() {
    RestCall createCycle = call("POST", "/api/performance/cycles", NEW_CYCLE, ADMIN, List.of());
    RestCall generate =
        call(
            "POST",
            "/api/performance/cycles/{cycleId}/generate-reviews",
            Map.of(),
            ADMIN,
            List.of());
    RestCall myReview =
        call("GET", "/api/performance/reviews/mine?cycleId={cycleId}", null, EMP_2, List.of());
    RestCall selfAssess =
        call(
            "POST",
            "/api/performance/reviews/{reviewId}/self-assessment",
            Map.of("selfAssessment", "x"),
            EMP_2,
            List.of());
    RestCall managerReview =
        call(
            "POST",
            "/api/performance/reviews/{reviewId}/manager-review",
            Map.of("overallRating", 3.0, "managerAssessment", "x"),
            ADMIN,
            List.of());
    List<RestCall> freshReview = List.of(createCycle, generate, myReview);
    RestCall addGoal =
        call(
            "POST",
            "/api/performance/reviews/{reviewId}/goals",
            Map.of("goalTitle", "Parallel-run goal", "weightPct", 20),
            EMP_2,
            List.of());
    return List.of(
        new Scenario(
            "performance.cycle.create",
            "performance",
            plsql(CREATE_CYCLE_PLSQL, List.of("cycle_id")),
            createCycle,
            Outcome.ok(Map.of("status", "DRAFT", "cycleName", "Parallel-run cycle"))),
        new Scenario(
            "performance.cycle.open.not-draft",
            "performance",
            plsql("begin pkg_performance.open_review_cycle(9001, :user); end;", List.of()),
            call("POST", "/api/performance/cycles/9001/open", Map.of(), ADMIN, List.of()),
            Outcome.error("-20401")),
        new Scenario(
            "performance.cycle.close.draft",
            "performance",
            plsql(
                CREATE_CYCLE_PLSQL.replace(
                    ":cycle_id := v_id;", "pkg_performance.close_review_cycle(v_id, :user);"),
                List.of()),
            call(
                "POST",
                "/api/performance/cycles/{cycleId}/close",
                Map.of(),
                ADMIN,
                List.of(createCycle)),
            Outcome.error("-20401")),
        new Scenario(
            "performance.review.self-assessment.wrong-status",
            "performance",
            plsql(
                freshReviewPlsql(
                    "pkg_performance.submit_self_assessment(v_review, 'x', :user); "
                        + "pkg_performance.submit_self_assessment(v_review, 'x', :user);"),
                List.of()),
            selfAssess.withSetup(List.of(createCycle, generate, myReview, selfAssess)),
            Outcome.error("-20402")),
        new Scenario(
            "performance.review.manager-review.wrong-status",
            "performance",
            plsql(
                freshReviewPlsql(
                    "pkg_performance.submit_manager_review(v_review, 3.0, 'x', null, null, null, :user);"),
                List.of()),
            managerReview.withSetup(freshReview), // NOT_STARTED: self-assessment not yet submitted
            Outcome.error("-20402")),
        new Scenario(
            "performance.review.acknowledge.wrong-status",
            "performance",
            plsql(
                freshReviewPlsql("pkg_performance.acknowledge_review(v_review, null, :user);"),
                List.of()),
            call(
                "POST",
                "/api/performance/reviews/{reviewId}/acknowledge",
                Map.of(),
                EMP_2,
                freshReview),
            Outcome.error("-20402")),
        new Scenario(
            "performance.review.manager-review.rating-out-of-range",
            "performance",
            plsql(
                freshReviewPlsql(
                    "pkg_performance.submit_self_assessment(v_review, 'x', :user); "
                        + "pkg_performance.submit_manager_review(v_review, 5.5, 'x', null, null, null, :user);"),
                List.of()),
            call(
                "POST",
                "/api/performance/reviews/{reviewId}/manager-review",
                Map.of("overallRating", 5.5, "managerAssessment", "x"),
                ADMIN,
                List.of(createCycle, generate, myReview, selfAssess)),
            Outcome.error("-20403")),
        new Scenario(
            "performance.goal.progress-100-completes",
            "performance",
            plsql(
                freshReviewPlsql(
                    "v_goal := pkg_performance.add_goal(v_review, 2, 'Parallel-run goal', null, "
                        + "'BUSINESS', 20, null, :user); "
                        + "pkg_performance.update_goal_progress(v_goal, 100, null, null, :user); "
                        + "select status into :status from performance_goals where goal_id = v_goal;"),
                List.of("status")),
            call(
                "PATCH",
                "/api/performance/goals/{goalId}/progress",
                Map.of("progressPct", 100),
                EMP_2,
                List.of(createCycle, generate, myReview, addGoal)),
            Outcome.ok(Map.of("status", "COMPLETED"))),
        new Scenario(
            "performance.cycle.generate-reviews.row-count",
            "performance",
            plsql(
                CREATE_CYCLE_PLSQL.replace(
                    ":cycle_id := v_id;",
                    "pkg_performance.generate_reviews_for_cycle(v_id, :user); "
                        + "select count(*) into :generated from performance_reviews where cycle_id = v_id;"),
                List.of("generated")),
            generate.withSetup(List.of(createCycle)),
            Outcome.ok(Map.of("generated", ELIGIBLE_EMPLOYEES, "skipped", "0"))),
        new Scenario(
            "performance.cycle.generate-reviews.idempotent",
            "performance",
            null, // legacy has no return value; the PERF-05 idempotency counts are target-only
            generate.withSetup(List.of(createCycle, generate)),
            Outcome.ok(Map.of("generated", "0", "skipped", ELIGIBLE_EMPLOYEES))));
  }

  /** Legacy leg mirroring the REST setup: fresh cycle + create_review(emp 2, reviewer 1). */
  private static String freshReviewPlsql(String body) {
    return "declare v_cycle number; v_review number; v_goal number; begin "
        + "v_cycle := pkg_performance.create_review_cycle('Parallel-run cycle', 2030, "
        + "date '2030-01-01', date '2030-12-31', null, null, :user); "
        + "v_review := pkg_performance.create_review(v_cycle, 2, 1, :user); "
        + body
        + " end;";
  }

  private static LegacyCall plsql(String block, List<String> outBinds) {
    return new LegacyCall(block, outBinds);
  }

  private static RestCall call(
      String method, String path, Map<String, Object> body, String auth, List<RestCall> setup) {
    return new RestCall(method, path, body, auth, false, setup);
  }
}
