package com.acme.hrms.validation.dto.performance;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/performance/reviews/{reviewId}/self-assessment. */
public class SelfAssessmentRequest {

  public static final int TEXT_MAX = 20000;

  @NotBlank
  @Size(min = 1, max = TEXT_MAX)
  @FieldMeta(trim = true, requiredMessage = "Self-assessment is required")
  private String selfAssessment;

  public String getSelfAssessment() {
    return selfAssessment;
  }

  public void setSelfAssessment(String selfAssessment) {
    this.selfAssessment = selfAssessment == null ? null : selfAssessment.trim();
  }
}
