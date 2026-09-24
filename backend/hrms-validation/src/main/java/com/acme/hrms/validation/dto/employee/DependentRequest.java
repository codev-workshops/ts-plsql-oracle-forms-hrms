package com.acme.hrms.validation.dto.employee;

import com.acme.hrms.validation.constraints.Ssn;
import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Body of POST/PUT /api/employees/{id}/dependents (EMPLOYEE_DEPENDENTS). */
public class DependentRequest extends StrictRequest {

  @NotBlank
  @Size(max = EmployeeRules.NAME_MAX)
  @FieldMeta(
      trim = true,
      requiredMessage = "First name is required",
      maxLengthMessage = "First name may not exceed 50 characters")
  private String firstName;

  @NotBlank
  @Size(max = EmployeeRules.NAME_MAX)
  @FieldMeta(
      trim = true,
      requiredMessage = "Last name is required",
      maxLengthMessage = "Last name may not exceed 50 characters")
  private String lastName;

  @NotNull
  @AllowedValues({"SPOUSE", "CHILD", "PARENT", "DOMESTIC_PARTNER", "OTHER"})
  @FieldMeta(requiredMessage = "Relationship is required")
  private String relationship;

  @PastOrPresent
  @FieldMeta(
      formatMessage = "Date of birth cannot be in the future",
      ruleId = "employee.dateNotFuture",
      ruleValue = "today",
      ruleErrorCode = "VALIDATION_FAILED",
      ruleMessage = "Date of birth cannot be in the future")
  private LocalDate dateOfBirth;

  @Ssn
  @Size(max = 11)
  @FieldMeta(
      trim = true,
      patternMessage = "SSN must be 9 digits (NNN-NN-NNNN)",
      ruleId = "employee.ssn",
      ruleErrorCode = "VALIDATION_FAILED",
      ruleMessage = "SSN must be 9 digits (NNN-NN-NNNN)",
      sensitive = true)
  private String ssn;

  @FieldMeta(formatMessage = "Benefits enrolment must be yes or no")
  private Boolean benefitsEnrolled;

  /** {@code false} on PUT soft-deletes the dependent (no DELETE route); ignored on POST. */
  private Boolean active;

  public Boolean getActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = EmployeeRules.blankToNull(firstName);
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = EmployeeRules.blankToNull(lastName);
  }

  public String getRelationship() {
    return relationship;
  }

  public void setRelationship(String relationship) {
    this.relationship = relationship;
  }

  public LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  public void setDateOfBirth(LocalDate dateOfBirth) {
    this.dateOfBirth = dateOfBirth;
  }

  public String getSsn() {
    return ssn;
  }

  public void setSsn(String ssn) {
    this.ssn = EmployeeRules.blankToNull(ssn);
  }

  public Boolean getBenefitsEnrolled() {
    return benefitsEnrolled;
  }

  public void setBenefitsEnrolled(Boolean benefitsEnrolled) {
    this.benefitsEnrolled = benefitsEnrolled;
  }
}
