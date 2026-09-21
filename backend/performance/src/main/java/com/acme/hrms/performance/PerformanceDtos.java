package com.acme.hrms.performance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.lang.Nullable;

/** Wire shapes of /api/performance/**, exactly as in contracts/p1-performance/openapi.yaml. */
public final class PerformanceDtos {

  private PerformanceDtos() {}

  /** {@code review_cycles} row = {@code ReviewCycle} schema (no derived columns). */
  public record ReviewCycle(
      long cycleId,
      String cycleName,
      int cycleYear,
      LocalDate startDate,
      LocalDate endDate,
      @Nullable LocalDate selfReviewDue,
      @Nullable LocalDate managerReviewDue,
      @Nullable LocalDate calibrationDue,
      String status,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record GenerateReviewsResult(long cycleId, int generated, int skipped) {}

  /** {@code performance_reviews} row plus the Forms POST-QUERY name lookups. */
  public record PerformanceReview(
      long reviewId,
      long cycleId,
      long empId,
      String employeeName,
      long reviewerEmpId,
      String reviewerName,
      String reviewType,
      String status,
      @Nullable BigDecimal overallRating,
      @Nullable String ratingLabel,
      @Nullable String selfAssessment,
      @Nullable String managerAssessment,
      @Nullable String strengths,
      @Nullable String areasForImprovement,
      @Nullable String developmentPlan,
      @Nullable String employeeComments,
      @Nullable LocalDate employeeAckDate,
      @Nullable BigDecimal calibratedRating,
      @Nullable String calibrationNotes,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record PageOfPerformanceReview(
      List<PerformanceReview> content, int page, int size, long totalElements, int totalPages) {}

  /** {@code performance_goals} row. */
  public record PerformanceGoal(
      long goalId,
      long reviewId,
      long empId,
      String goalTitle,
      @Nullable String goalDescription,
      String goalCategory,
      BigDecimal weightPct,
      @Nullable LocalDate targetDate,
      String status,
      BigDecimal progressPct,
      @Nullable BigDecimal selfRating,
      @Nullable BigDecimal managerRating,
      @Nullable String comments,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record TeamReviewRow(
      long reviewId,
      long empId,
      String employeeName,
      String jobTitle,
      String deptName,
      String status,
      @Nullable BigDecimal overallRating,
      @Nullable String ratingLabel) {}

  public record RatingDistributionRow(String ratingLabel, long count, BigDecimal percentage) {}
}
