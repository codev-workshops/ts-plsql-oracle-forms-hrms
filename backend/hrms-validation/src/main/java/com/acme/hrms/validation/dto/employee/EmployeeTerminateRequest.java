package com.acme.hrms.validation.dto.employee;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Body of POST /api/employees/{id}/terminate (PKG_EMPLOYEE.terminate_employee). */
public class EmployeeTerminateRequest {

  @NotNull
  @FieldMeta(requiredMessage = "Termination date is required")
  private LocalDate effectiveDate;

  @NotBlank
  @Size(max = EmployeeRules.TERMINATION_REASON_MAX)
  @FieldMeta(trim = true, requiredMessage = "Termination reason is required")
  private String reason;

  @Size(max = EmployeeRules.COMMENTS_MAX)
  @FieldMeta(trim = true)
  private String comments;

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = EmployeeRules.blankToNull(reason);
  }

  public String getComments() {
    return comments;
  }

  public void setComments(String comments) {
    this.comments = EmployeeRules.blankToNull(comments);
  }
}
