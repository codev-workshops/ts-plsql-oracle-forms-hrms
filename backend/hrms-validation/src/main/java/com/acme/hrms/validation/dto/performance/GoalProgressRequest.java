package com.acme.hrms.validation.dto.performance;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Body of PATCH /api/performance/goals/{goalId}/progress. */
public class GoalProgressRequest {

  @NotNull
  @DecimalMin("0")
  @DecimalMax("100")
  @Digits(integer = 3, fraction = 2)
  @FieldMeta(
      requiredMessage = "Progress is required",
      formatMessage = "Progress must be between 0 and 100")
  private BigDecimal progressPct;

  @AllowedValues({"NOT_STARTED", "IN_PROGRESS", "COMPLETED", "DEFERRED", "CANCELLED"})
  private String status;

  @Size(max = SelfAssessmentRequest.TEXT_MAX)
  @FieldMeta(trim = true)
  private String comments;

  public BigDecimal getProgressPct() {
    return progressPct;
  }

  public void setProgressPct(BigDecimal progressPct) {
    this.progressPct = progressPct;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getComments() {
    return comments;
  }

  public void setComments(String comments) {
    this.comments = ManagerReviewRequest.blankToNull(comments);
  }
}
