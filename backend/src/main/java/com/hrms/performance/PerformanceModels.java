package com.hrms.performance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Request and response records of the performance API (contract amendment v1.1-P4). */
public final class PerformanceModels {

    private PerformanceModels() {
    }

    public enum CycleStatus { DRAFT, OPEN, IN_PROGRESS, CALIBRATION, CLOSED }

    public enum ReviewStatus { NOT_STARTED, SELF_REVIEW, MANAGER_REVIEW, MEETING_SCHEDULED, COMPLETED, ACKNOWLEDGED }

    public enum GoalStatus { NOT_STARTED, IN_PROGRESS, COMPLETED, DEFERRED, CANCELLED }

    public enum GoalCategory { BUSINESS, DEVELOPMENT, LEADERSHIP, INNOVATION, COMPLIANCE }

    public enum ReviewRole { EMPLOYEE, REVIEWER }

    public record ReviewCycle(long cycleId, String cycleName, int cycleYear, LocalDate startDate, LocalDate endDate,
            LocalDate selfReviewDue, LocalDate managerReviewDue, LocalDate calibrationDue, CycleStatus status,
            String createdBy, OffsetDateTime createdDate, String modifiedBy, OffsetDateTime modifiedDate) {
    }

    public record CreateReviewCycle(@NotBlank @Size(max = 100) String cycleName,
            @NotNull @Min(1900) @Max(9999) Integer cycleYear, @NotNull LocalDate startDate,
            @NotNull LocalDate endDate, LocalDate selfReviewDue, LocalDate managerReviewDue,
            LocalDate calibrationDue) {
    }

    public record PerformanceReview(long reviewId, long cycleId, String cycleName, long empId, String employeeName,
            String jobTitle, String deptName, long reviewerEmpId, String reviewerName, String reviewType,
            ReviewStatus status, BigDecimal overallRating, String ratingLabel, String selfAssessment,
            String managerAssessment, String strengths, String areasForImprovement, String developmentPlan,
            String employeeComments, OffsetDateTime employeeAckDate, OffsetDateTime createdDate,
            OffsetDateTime modifiedDate) {
    }

    public record CreateReview(@NotNull Long empId, @NotNull Long reviewerEmpId) {
    }

    public record GenerateReviewsResult(long cycleId, int reviewsCreated, int employeesSkipped) {
    }

    public record SelfAssessment(@NotBlank @Size(max = 32000) String selfAssessment) {
    }

    public record ManagerReview(@NotNull BigDecimal overallRating, @Size(max = 32000) String managerAssessment,
            @Size(max = 32000) String strengths, @Size(max = 32000) String areasForImprovement,
            @Size(max = 32000) String developmentPlan) {
    }

    public record Acknowledgement(@Size(max = 32000) String comments) {
    }

    public record RatingBucket(String ratingLabel, int reviewCount, BigDecimal percentage) {
    }

    public record PerformanceGoal(long goalId, long reviewId, long empId, String goalTitle, String goalDescription,
            GoalCategory goalCategory, BigDecimal weightPct, LocalDate targetDate, GoalStatus status,
            BigDecimal progressPct, String comments, String createdBy, OffsetDateTime createdDate,
            String modifiedBy, OffsetDateTime modifiedDate) {
    }

    public record AddGoal(@NotBlank @Size(max = 200) String goalTitle, @Size(max = 32000) String goalDescription,
            GoalCategory goalCategory,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal weightPct,
            LocalDate targetDate) {
    }

    public record GoalProgress(
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal progressPct,
            GoalStatus status, @Size(max = 32000) String comments) {
    }
}
