package com.acme.hrms.validation.dto.performance;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of POST /api/performance/reviews/{reviewId}/manager-review. The rating bounds are the
 * PKG_PERFORMANCE.submit_manager_review guard (-20403) and CHK_RATING_RANGE.
 */
public class ManagerReviewRequest {

  public static final String RATING_MIN = "1.0";
  public static final String RATING_MAX = "5.0";

  @NotNull
  @DecimalMin(RATING_MIN)
  @DecimalMax(RATING_MAX)
  @Digits(integer = 1, fraction = 1)
  @FieldMeta(
      requiredMessage = "Rating is required",
      ruleId = "rating.range",
      ruleErrorCode = "-20403",
      ruleMessage = "Rating must be between 1.0 and 5.0")
  private BigDecimal overallRating;

  @NotBlank
  @Size(min = 1, max = SelfAssessmentRequest.TEXT_MAX)
  @FieldMeta(trim = true, requiredMessage = "Manager assessment is required")
  private String managerAssessment;

  @Size(max = SelfAssessmentRequest.TEXT_MAX)
  @FieldMeta(trim = true)
  private String strengths;

  @Size(max = SelfAssessmentRequest.TEXT_MAX)
  @FieldMeta(trim = true)
  private String improvementAreas;

  @Size(max = SelfAssessmentRequest.TEXT_MAX)
  @FieldMeta(trim = true)
  private String developmentPlan;

  public BigDecimal getOverallRating() {
    return overallRating;
  }

  public void setOverallRating(BigDecimal overallRating) {
    this.overallRating = overallRating;
  }

  public String getManagerAssessment() {
    return managerAssessment;
  }

  public void setManagerAssessment(String managerAssessment) {
    this.managerAssessment = managerAssessment == null ? null : managerAssessment.trim();
  }

  public String getStrengths() {
    return strengths;
  }

  public void setStrengths(String strengths) {
    this.strengths = blankToNull(strengths);
  }

  public String getImprovementAreas() {
    return improvementAreas;
  }

  public void setImprovementAreas(String improvementAreas) {
    this.improvementAreas = blankToNull(improvementAreas);
  }

  public String getDevelopmentPlan() {
    return developmentPlan;
  }

  public void setDevelopmentPlan(String developmentPlan) {
    this.developmentPlan = blankToNull(developmentPlan);
  }

  static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
