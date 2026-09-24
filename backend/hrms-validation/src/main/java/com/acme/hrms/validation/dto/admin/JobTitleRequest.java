package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST/PUT /api/admin/job-titles (JOB_TITLES; grade must be active -> -20604). */
public class JobTitleRequest {

  @NotBlank
  @Size(min = 1, max = 20)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Job code is required",
      patternMessage = AdminRules.CODE_MESSAGE)
  private String jobCode;

  @NotBlank
  @Size(min = 1, max = 100)
  @FieldMeta(trim = true, requiredMessage = "Job title is required")
  private String jobTitle;

  @Size(max = 50)
  @FieldMeta(trim = true)
  private String jobFamily;

  @NotNull
  @Min(1)
  @FieldMeta(requiredMessage = "Grade is required")
  private Integer gradeId;

  @Size(max = 10)
  @FieldMeta(trim = true)
  private String eeoCategory;

  @AllowedValues({"EXEMPT", "NON_EXEMPT"})
  private String flsaStatus;

  @FieldMeta private Boolean activeFlag;

  public String getJobCode() {
    return jobCode;
  }

  public void setJobCode(String jobCode) {
    this.jobCode = jobCode == null || jobCode.isBlank() ? null : jobCode.trim();
  }

  public String getJobTitle() {
    return jobTitle;
  }

  public void setJobTitle(String jobTitle) {
    this.jobTitle = jobTitle == null || jobTitle.isBlank() ? null : jobTitle.trim();
  }

  public String getJobFamily() {
    return jobFamily;
  }

  public void setJobFamily(String jobFamily) {
    this.jobFamily = jobFamily == null || jobFamily.isBlank() ? null : jobFamily.trim();
  }

  public Integer getGradeId() {
    return gradeId;
  }

  public void setGradeId(Integer gradeId) {
    this.gradeId = gradeId;
  }

  public String getEeoCategory() {
    return eeoCategory;
  }

  public void setEeoCategory(String eeoCategory) {
    this.eeoCategory = eeoCategory == null || eeoCategory.isBlank() ? null : eeoCategory.trim();
  }

  public String getFlsaStatus() {
    return flsaStatus;
  }

  public void setFlsaStatus(String flsaStatus) {
    this.flsaStatus = flsaStatus;
  }

  public Boolean getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Boolean activeFlag) {
    this.activeFlag = activeFlag;
  }
}
