package com.acme.hrms.validation.dto.employee;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of POST /api/employees/{id}/salary (salary-module; PKG_PAYROLL.create_salary_record
 * semantics).
 */
public class SalaryChangeRequest extends StrictRequest {

  @NotNull
  @FieldMeta(requiredMessage = "Effective date is required")
  private LocalDate effectiveDate;

  @NotNull
  @DecimalMin(value = "0.01")
  @Digits(integer = 10, fraction = 2)
  @FieldMeta(
      requiredMessage = "Salary is required",
      ruleId = "salary.positive",
      ruleErrorCode = "-20101",
      ruleMessage = "Salary must be positive")
  private BigDecimal baseSalary;

  @Pattern(regexp = "^[A-Z]{3}$")
  @FieldMeta(trim = true, patternMessage = "Currency must be a 3-letter ISO code")
  private String currencyCode;

  @AllowedValues({"WEEKLY", "BIWEEKLY", "SEMIMONTHLY", "MONTHLY"})
  private String payFrequency;

  @AllowedValues({"ANNUAL", "HOURLY"})
  private String salaryBasis;

  @NotBlank
  @Size(max = EmployeeRules.CHANGE_REASON_MAX)
  @FieldMeta(trim = true, requiredMessage = "Change reason is required")
  private String changeReason;

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public BigDecimal getBaseSalary() {
    return baseSalary;
  }

  public void setBaseSalary(BigDecimal baseSalary) {
    this.baseSalary = baseSalary;
  }

  public String getCurrencyCode() {
    return currencyCode;
  }

  public void setCurrencyCode(String currencyCode) {
    this.currencyCode = EmployeeRules.blankToNull(currencyCode);
  }

  public String getPayFrequency() {
    return payFrequency;
  }

  public void setPayFrequency(String payFrequency) {
    this.payFrequency = payFrequency;
  }

  public String getSalaryBasis() {
    return salaryBasis;
  }

  public void setSalaryBasis(String salaryBasis) {
    this.salaryBasis = salaryBasis;
  }

  public String getChangeReason() {
    return changeReason;
  }

  public void setChangeReason(String changeReason) {
    this.changeReason = EmployeeRules.blankToNull(changeReason);
  }
}
