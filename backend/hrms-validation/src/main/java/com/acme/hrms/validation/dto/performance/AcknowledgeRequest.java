package com.acme.hrms.validation.dto.performance;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Size;

/** Body of POST /api/performance/reviews/{reviewId}/acknowledge (optional). */
public class AcknowledgeRequest {

  @Size(max = SelfAssessmentRequest.TEXT_MAX)
  @FieldMeta(trim = true)
  private String employeeComments;

  public String getEmployeeComments() {
    return employeeComments;
  }

  public void setEmployeeComments(String employeeComments) {
    this.employeeComments = ManagerReviewRequest.blankToNull(employeeComments);
  }
}
