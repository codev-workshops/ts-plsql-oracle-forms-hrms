package com.acme.hrms.validation.dto.employee;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Body of POST /api/employees/{id}/transfer (PKG_EMPLOYEE.transfer_employee; a job change here is
 * the promote_employee path).
 */
public class EmployeeTransferRequest {

  @NotNull
  @FieldMeta(requiredMessage = "Effective date is required")
  private LocalDate effectiveDate;

  @NotNull
  @Min(1)
  @FieldMeta(
      requiredMessage = "Department is required",
      formatMessage = "Select an active department")
  private Integer deptId;

  @Min(1)
  @FieldMeta(formatMessage = "Select an active job title")
  private Integer newJobId;

  @Min(1)
  @FieldMeta(formatMessage = "Select an active manager (not the employee themselves)")
  private Integer newManagerEmpId;

  @Pattern(regexp = EmployeeRules.LOCATION_CODE_PATTERN)
  @FieldMeta(trim = true, patternMessage = "Select a location")
  private String newLocationCode;

  @Size(max = EmployeeRules.REASON_CODE_MAX)
  @FieldMeta(trim = true)
  private String reasonCode;

  @Size(max = EmployeeRules.COMMENTS_MAX)
  @FieldMeta(trim = true)
  private String comments;

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public Integer getDeptId() {
    return deptId;
  }

  public void setDeptId(Integer deptId) {
    this.deptId = deptId;
  }

  public Integer getNewJobId() {
    return newJobId;
  }

  public void setNewJobId(Integer newJobId) {
    this.newJobId = newJobId;
  }

  public Integer getNewManagerEmpId() {
    return newManagerEmpId;
  }

  public void setNewManagerEmpId(Integer newManagerEmpId) {
    this.newManagerEmpId = newManagerEmpId;
  }

  public String getNewLocationCode() {
    return newLocationCode;
  }

  public void setNewLocationCode(String newLocationCode) {
    this.newLocationCode = EmployeeRules.blankToNull(newLocationCode);
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public void setReasonCode(String reasonCode) {
    this.reasonCode = EmployeeRules.blankToNull(reasonCode);
  }

  public String getComments() {
    return comments;
  }

  public void setComments(String comments) {
    this.comments = EmployeeRules.blankToNull(comments);
  }
}
