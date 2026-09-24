package com.acme.hrms.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.performance.PerformanceDtos.PageOfPerformanceReview;
import com.acme.hrms.performance.PerformanceDtos.PerformanceGoal;
import com.acme.hrms.performance.PerformanceDtos.PerformanceReview;
import com.acme.hrms.performance.PerformanceDtos.RatingDistributionRow;
import com.acme.hrms.performance.PerformanceDtos.ReviewCycle;
import com.acme.hrms.performance.PerformanceDtos.TeamReviewRow;
import com.acme.hrms.validation.dto.performance.ReviewCycleRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 1 repository tests on PostgreSQL (Testcontainers) with the frozen seed: cycle / review /
 * goal queries, the set-based PERF-05 generation and the V3 uniqueness that makes it idempotent.
 */
class PerformanceRepositoryTest {

  private static JdbcTemplate jdbc;
  private static ReviewCycleRepository cycles;
  private static PerformanceReviewRepository reviews;
  private static PerformanceGoalRepository goals;

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
    cycles = new ReviewCycleRepository(jdbc);
    reviews = new PerformanceReviewRepository(jdbc);
    goals = new PerformanceGoalRepository(jdbc);
  }

  @Test
  void cycleCrudAndGuardedTransitions() {
    ReviewCycleRequest req = new ReviewCycleRequest();
    req.setCycleName("Repo cycle");
    req.setCycleYear(2031);
    req.setStartDate(LocalDate.of(2031, 1, 1));
    req.setEndDate(LocalDate.of(2031, 12, 31));
    long id = cycles.insert(req, "1");
    ReviewCycle c = cycles.findById(id).orElseThrow();
    assertThat(c.status()).isEqualTo("DRAFT");
    assertThat(c.createdBy()).isEqualTo("1");
    assertThat(c.selfReviewDue()).isNull();

    assertThat(cycles.list(List.of("DRAFT"), "cycle_year desc"))
        .extracting(ReviewCycle::cycleId)
        .contains(id);
    assertThat(cycles.list(List.of("IN_PROGRESS"), "start_date asc"))
        .extracting(ReviewCycle::cycleId)
        .containsExactly(9001L);

    assertThat(cycles.transition(id, ReviewCycleService.CLOSE_FROM, "CLOSED", "1")).isZero();
    assertThat(cycles.transition(id, ReviewCycleService.OPEN_FROM, "OPEN", "1")).isEqualTo(1);
    assertThat(cycles.transition(id, ReviewCycleService.OPEN_FROM, "OPEN", "1")).isZero();
    req.setCycleName("edited");
    assertThat(cycles.update(id, req, "1")).isZero(); // not DRAFT any more
    assertThat(cycles.transition(id, ReviewCycleService.CLOSE_FROM, "CLOSED", "1")).isEqualTo(1);
    assertThat(cycles.findById(id).orElseThrow().modifiedBy()).isEqualTo("1");
    assertThat(cycles.findById(424242L)).isEmpty();
  }

  @Test
  void generationIsSetBasedIdempotentAndUniquePerCycleEmployee() {
    ReviewCycleRequest req = new ReviewCycleRequest();
    req.setCycleName("Gen cycle");
    req.setCycleYear(2032);
    req.setStartDate(LocalDate.of(2032, 1, 1));
    req.setEndDate(LocalDate.of(2032, 12, 31));
    long cycleId = cycles.insert(req, "1");

    int eligible = reviews.countEligible();
    assertThat(eligible).isEqualTo(22);
    List<Long> created = reviews.generateForCycle(cycleId, "1");
    assertThat(created).hasSize(eligible);
    assertThat(reviews.generateForCycle(cycleId, "1")).isEmpty();

    PerformanceReview r = reviews.findById(created.get(0)).orElseThrow();
    assertThat(r.cycleId()).isEqualTo(cycleId);
    assertThat(r.status()).isEqualTo("NOT_STARTED");
    assertThat(r.reviewType()).isEqualTo("ANNUAL");
    assertThat(r.createdBy()).isEqualTo("1");
    assertThat(r.employeeName()).isNotBlank();
    assertThat(r.reviewerName()).isNotBlank();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into performance_reviews (review_id, cycle_id, emp_id, reviewer_emp_id,"
                        + " created_by) values (nextval('seq_perf_review'), ?, ?, ?, 'dup')",
                    cycleId,
                    r.empId(),
                    r.reviewerEmpId()))
        .isInstanceOf(DuplicateKeyException.class);

    PageOfPerformanceReview page = reviews.pageByCycle(cycleId, List.of(), 1, 10);
    assertThat(page.totalElements()).isEqualTo(eligible);
    assertThat(page.totalPages()).isEqualTo(3);
    assertThat(page.content()).hasSize(10);
    assertThat(reviews.pageByCycle(cycleId, List.of("COMPLETED"), 0, 10).totalElements()).isZero();

    assertThat(reviews.listMine(2, cycleId)).hasSize(1);
    assertThat(reviews.listMine(2, null)).extracting(PerformanceReview::cycleId).contains(cycleId);
    assertThat(reviews.listMine(1, null)).isEmpty(); // CEO has no manager -> never generated
  }

  @Test
  void reviewTransitionsAreGuardedUpdates() {
    // seed: 5003 SELF_REVIEW, 5001 MANAGER_REVIEW, 5004 COMPLETED
    assertThat(reviews.submitSelfAssessment(5001, "x", "22")).isZero();
    assertThat(reviews.submitSelfAssessment(5003, "my year", "23")).isEqualTo(1);
    assertThat(reviews.findById(5003).orElseThrow().status()).isEqualTo("MANAGER_REVIEW");

    assertThat(
            reviews.submitManagerReview(
                5004, new BigDecimal("3.0"), "Meets Expectations", "m", null, null, null, "41"))
        .isZero();
    assertThat(
            reviews.submitManagerReview(
                5003, new BigDecimal("3.7"), "Exceeds Expectations", "m", "s", "a", "d", "21"))
        .isEqualTo(1);
    PerformanceReview r = reviews.findById(5003).orElseThrow();
    assertThat(r.status()).isEqualTo("COMPLETED");
    assertThat(r.overallRating()).isEqualByComparingTo("3.7");
    assertThat(r.ratingLabel()).isEqualTo("Exceeds Expectations");
    assertThat(r.areasForImprovement()).isEqualTo("a");

    assertThat(reviews.acknowledge(5001, null, "22")).isZero();
    assertThat(reviews.acknowledge(5003, "ok", "23")).isEqualTo(1);
    r = reviews.findById(5003).orElseThrow();
    assertThat(r.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(r.employeeAckDate()).isEqualTo(LocalDate.now());
    assertThat(r.employeeComments()).isEqualTo("ok");
  }

  @Test
  void teamReviewsAndRatingDistributionQueries() {
    List<TeamReviewRow> team = reviews.teamReviews(21, 9001);
    assertThat(team).extracting(TeamReviewRow::empId).containsExactly(22L, 23L);
    assertThat(team.get(0).jobTitle()).isNotBlank();
    assertThat(team.get(0).deptName()).isNotBlank();
    assertThat(reviews.teamReviews(11, 9001)).isEmpty();

    List<RatingDistributionRow> dist = reviews.ratingDistribution(9001, null);
    assertThat(dist)
        .anySatisfy(
            row -> {
              assertThat(row.ratingLabel()).isEqualTo("Exceeds Expectations");
              assertThat(row.count()).isGreaterThanOrEqualTo(1);
            });
    assertThat(
            dist.stream()
                .map(RatingDistributionRow::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("100.0");
    assertThat(reviews.ratingDistribution(9001, 40L))
        .extracting(RatingDistributionRow::count)
        .containsExactly(1L);
    assertThat(reviews.ratingDistribution(9001, 10L)).isEmpty();
  }

  @Test
  void goalInsertListAndProgress() {
    long id =
        goals.insert(
            5001,
            22,
            "Ship",
            null,
            "DEVELOPMENT",
            new BigDecimal("25.5"),
            LocalDate.of(2024, 12, 31),
            "22");
    PerformanceGoal g = goals.findById(id).orElseThrow();
    assertThat(g.status()).isEqualTo("NOT_STARTED");
    assertThat(g.progressPct()).isEqualByComparingTo("0");
    assertThat(g.weightPct()).isEqualByComparingTo("25.5");
    assertThat(g.goalCategory()).isEqualTo("DEVELOPMENT");
    assertThat(goals.listByReview(5001)).extracting(PerformanceGoal::goalId).contains(id);

    assertThat(goals.updateProgress(id, new BigDecimal("40"), "IN_PROGRESS", "half", "22"))
        .isEqualTo(1);
    g = goals.findById(id).orElseThrow();
    assertThat(g.progressPct()).isEqualByComparingTo("40");
    assertThat(g.comments()).isEqualTo("half");
    assertThat(goals.updateProgress(id, new BigDecimal("100"), "COMPLETED", null, "22"))
        .isEqualTo(1);
    g = goals.findById(id).orElseThrow();
    assertThat(g.status()).isEqualTo("COMPLETED");
    assertThat(g.comments()).isEqualTo("half"); // null comment keeps the previous one
    assertThat(goals.findById(999999L)).isEmpty();
  }
}
