package com.acme.hrms.validation.dto.performance;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Body of POST /api/performance/reviews/{reviewId}/goals. */
public class GoalRequest {

  @NotBlank
  @Size(min = 1, max = 200)
  @FieldMeta(trim = true, requiredMessage = "Goal title is required")
  private String goalTitle;

  @Size(max = SelfAssessmentRequest.TEXT_MAX)
  @FieldMeta(trim = true)
  private String goalDescription;

  @AllowedValues({"BUSINESS", "DEVELOPMENT", "LEADERSHIP", "INNOVATION", "COMPLIANCE"})
  private String goalCategory;

  @DecimalMin("0")
  @DecimalMax("100")
  @Digits(integer = 3, fraction = 2)
  @FieldMeta(formatMessage = "Weight must be between 0 and 100")
  private BigDecimal weightPct;

  @FieldMeta private LocalDate targetDate;

  public String getGoalTitle() {
    return goalTitle;
  }

  public void setGoalTitle(String goalTitle) {
    this.goalTitle = goalTitle == null ? null : goalTitle.trim();
  }

  public String getGoalDescription() {
    return goalDescription;
  }

  public void setGoalDescription(String goalDescription) {
    this.goalDescription = ManagerReviewRequest.blankToNull(goalDescription);
  }

  public String getGoalCategory() {
    return goalCategory;
  }

  public void setGoalCategory(String goalCategory) {
    this.goalCategory = goalCategory;
  }

  public BigDecimal getWeightPct() {
    return weightPct;
  }

  public void setWeightPct(BigDecimal weightPct) {
    this.weightPct = weightPct;
  }

  public LocalDate getTargetDate() {
    return targetDate;
  }

  public void setTargetDate(LocalDate targetDate) {
    this.targetDate = targetDate;
  }
}
