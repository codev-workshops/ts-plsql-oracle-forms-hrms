package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Body of POST/PUT /api/admin/job-grades (JOB_GRADES; CHK_SALARY_RANGE max >= min -> -20603). */
public class JobGradeRequest {

  @NotBlank
  @Size(min = 1, max = 10)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Grade code is required",
      patternMessage = AdminRules.CODE_MESSAGE)
  private String gradeCode;

  @NotBlank
  @Size(min = 1, max = 50)
  @FieldMeta(trim = true, requiredMessage = "Grade name is required")
  private String gradeName;

  @NotNull
  @DecimalMin(value = "0.00")
  @Digits(integer = 10, fraction = 2)
  @FieldMeta(requiredMessage = "Minimum salary is required")
  private BigDecimal minSalary;

  @NotNull
  @DecimalMin(value = "0.00")
  @Digits(integer = 10, fraction = 2)
  @FieldMeta(requiredMessage = "Maximum salary is required")
  private BigDecimal maxSalary;

  @FieldMeta private Boolean overtimeEligible;

  @FieldMeta private Boolean activeFlag;

  @AssertTrue(message = "maxSalary must be greater than or equal to minSalary")
  public boolean isSalaryRangeValid() {
    return minSalary == null || maxSalary == null || maxSalary.compareTo(minSalary) >= 0;
  }

  public String getGradeCode() {
    return gradeCode;
  }

  public void setGradeCode(String gradeCode) {
    this.gradeCode = gradeCode == null || gradeCode.isBlank() ? null : gradeCode.trim();
  }

  public String getGradeName() {
    return gradeName;
  }

  public void setGradeName(String gradeName) {
    this.gradeName = gradeName == null || gradeName.isBlank() ? null : gradeName.trim();
  }

  public BigDecimal getMinSalary() {
    return minSalary;
  }

  public void setMinSalary(BigDecimal minSalary) {
    this.minSalary = minSalary;
  }

  public BigDecimal getMaxSalary() {
    return maxSalary;
  }

  public void setMaxSalary(BigDecimal maxSalary) {
    this.maxSalary = maxSalary;
  }

  public Boolean getOvertimeEligible() {
    return overtimeEligible;
  }

  public void setOvertimeEligible(Boolean overtimeEligible) {
    this.overtimeEligible = overtimeEligible;
  }

  public Boolean getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Boolean activeFlag) {
    this.activeFlag = activeFlag;
  }
}
