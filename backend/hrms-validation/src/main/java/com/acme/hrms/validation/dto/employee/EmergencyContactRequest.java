package com.acme.hrms.validation.dto.employee;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST/PUT /api/employees/{id}/contacts (EMERGENCY_CONTACTS). */
public class EmergencyContactRequest {

  @NotBlank
  @Size(max = 100)
  @FieldMeta(trim = true, requiredMessage = "Contact name is required")
  private String contactName;

  @Size(max = 30)
  @FieldMeta(trim = true)
  private String relationship;

  @NotBlank
  @Pattern(regexp = EmployeeRules.PHONE_PATTERN, message = EmployeeRules.PHONE_MESSAGE)
  @Size(max = EmployeeRules.PHONE_MAX)
  @FieldMeta(
      trim = true,
      patternMessage = EmployeeRules.PHONE_MESSAGE,
      requiredMessage = "Primary phone is required")
  private String phonePrimary;

  @Pattern(regexp = EmployeeRules.PHONE_PATTERN, message = EmployeeRules.PHONE_MESSAGE)
  @Size(max = EmployeeRules.PHONE_MAX)
  @FieldMeta(trim = true, patternMessage = EmployeeRules.PHONE_MESSAGE)
  private String phoneSecondary;

  @Email
  @Size(max = EmployeeRules.EMAIL_MAX)
  @FieldMeta(trim = true, formatMessage = "Enter a valid e-mail address")
  private String email;

  @Min(1)
  @Max(99)
  private Integer priorityOrder;

  /** {@code false} on PUT soft-deletes the contact (no DELETE route); ignored on POST. */
  private Boolean active;

  public Boolean getActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  public String getContactName() {
    return contactName;
  }

  public void setContactName(String contactName) {
    this.contactName = EmployeeRules.blankToNull(contactName);
  }

  public String getRelationship() {
    return relationship;
  }

  public void setRelationship(String relationship) {
    this.relationship = EmployeeRules.blankToNull(relationship);
  }

  public String getPhonePrimary() {
    return phonePrimary;
  }

  public void setPhonePrimary(String phonePrimary) {
    this.phonePrimary = EmployeeRules.blankToNull(phonePrimary);
  }

  public String getPhoneSecondary() {
    return phoneSecondary;
  }

  public void setPhoneSecondary(String phoneSecondary) {
    this.phoneSecondary = EmployeeRules.blankToNull(phoneSecondary);
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = EmployeeRules.blankToNull(email);
  }

  public Integer getPriorityOrder() {
    return priorityOrder;
  }

  public void setPriorityOrder(Integer priorityOrder) {
    this.priorityOrder = priorityOrder;
  }
}
