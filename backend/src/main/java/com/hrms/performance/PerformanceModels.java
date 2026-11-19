package com.hrms.performance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Performance API payloads (openapi-v1.yaml, tag "performance"). */
public final class PerformanceModels {

    private PerformanceModels() {
    }

    public record ReviewCycle(
            Long cycleId,
            @NotBlank @Size(max = 100) String cycleName,
            Integer cycleYear,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            LocalDate selfReviewDeadline,
            LocalDate managerReviewDeadline,
            String status) { }

    public record PerformanceGoal(
            Long goalId,
            @NotBlank @Size(max = 200) String goalTitle,
            @Size(max = 4000) String goalDescription,
            @DecimalMin("0") @DecimalMax("100") BigDecimal weightPct,
            LocalDate targetDate,
            BigDecimal progressPct,
            String status) { }

    public record PerformanceReview(long reviewId, long cycleId, long empId, String employeeName,
                                    long reviewerEmpId, String reviewerName, String status, String selfAssessment,
                                    BigDecimal selfRating, String managerComments, BigDecimal overallRating,
                                    String ratingLabel, List<PerformanceGoal> goals) {

        PerformanceReview withGoals(List<PerformanceGoal> g) {
            return new PerformanceReview(reviewId, cycleId, empId, employeeName, reviewerEmpId, reviewerName, status,
                    selfAssessment, selfRating, managerComments, overallRating, ratingLabel, g);
        }
    }

    public record CreateReviewRequest(@NotNull Long cycleId, @NotNull Long empId, Long reviewerEmpId) { }

    public record SelfAssessmentRequest(@NotBlank @Size(max = 32000) String selfAssessment, BigDecimal selfRating) { }

    public record ManagerReviewRequest(@NotNull BigDecimal overallRating, @Size(max = 32000) String managerComments,
                                       @Size(max = 32000) String strengths,
                                       @Size(max = 32000) String improvements) { }

    public record GoalProgressRequest(@NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal progressPct,
                                      String status) { }

    public record RatingCount(String ratingLabel, int count) { }

    public record BatchResult(String operation, int rowsAffected) { }
}
