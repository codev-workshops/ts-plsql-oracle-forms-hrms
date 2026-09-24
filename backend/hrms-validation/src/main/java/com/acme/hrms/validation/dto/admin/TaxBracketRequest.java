package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Body of POST/PUT /api/admin/tax-brackets (TAX_BRACKETS; CHK_FILING_STATUS as widened by V7).
 * Shape rules -> -20603 (federal step: stateCode null and filingStatus != ALL; state row: 2-letter
 * stateCode, ALL, bracketMin 0, bracketMax null); overlap -> -20608; locked tax year -> -20609.
 */
public class TaxBracketRequest {

  public static final String STATE_CODE_PATTERN = "^[A-Z]{2}$";

  @NotNull
  @Min(2000)
  @Max(2100)
  @FieldMeta(requiredMessage = "Tax year is required")
  private Integer taxYear;

  @NotNull
  @AllowedValues({"SINGLE", "MARRIED_JOINT", "MARRIED_SEPARATE", "HEAD_OF_HOUSEHOLD", "ALL"})
  @FieldMeta(requiredMessage = "Filing status is required")
  private String filingStatus;

  @Pattern(regexp = STATE_CODE_PATTERN)
  @FieldMeta(trim = true, patternMessage = "State code must be two upper-case letters")
  private String stateCode;

  @NotNull
  @DecimalMin(value = "0.00")
  @Digits(integer = 10, fraction = 2)
  @FieldMeta(requiredMessage = "Bracket minimum is required")
  private BigDecimal bracketMin;

  @DecimalMin(value = "0.00", inclusive = false)
  @Digits(integer = 10, fraction = 2)
  private BigDecimal bracketMax;

  @NotNull
  @DecimalMin(value = "0.0000")
  @DecimalMax(value = "1.0000")
  @Digits(integer = 1, fraction = 4)
  @FieldMeta(
      requiredMessage = "Tax rate is required",
      ruleId = "tax.rateFraction",
      ruleErrorCode = "-20603",
      ruleMessage = "Tax rate is a fraction between 0 and 1 (0.22, never 22)")
  private BigDecimal taxRate;

  @DecimalMin(value = "0.00")
  @Digits(integer = 10, fraction = 2)
  private BigDecimal baseTax;

  @FieldMeta private Boolean activeFlag;

  @AssertTrue(message = "bracketMax must be greater than bracketMin")
  public boolean isBracketRangeValid() {
    return bracketMin == null || bracketMax == null || bracketMax.compareTo(bracketMin) > 0;
  }

  @AssertTrue(message = "A federal step (no stateCode) must not use filingStatus ALL")
  public boolean isFederalStepShape() {
    return stateCode != null || !"ALL".equals(filingStatus);
  }

  @AssertTrue(message = "A state row needs filingStatus ALL, bracketMin 0 and no bracketMax")
  public boolean isStateRowShape() {
    return stateCode == null
        || ("ALL".equals(filingStatus)
            && bracketMax == null
            && (bracketMin == null || bracketMin.signum() == 0));
  }

  public Integer getTaxYear() {
    return taxYear;
  }

  public void setTaxYear(Integer taxYear) {
    this.taxYear = taxYear;
  }

  public String getFilingStatus() {
    return filingStatus;
  }

  public void setFilingStatus(String filingStatus) {
    this.filingStatus = filingStatus;
  }

  public String getStateCode() {
    return stateCode;
  }

  public void setStateCode(String stateCode) {
    this.stateCode = stateCode == null || stateCode.isBlank() ? null : stateCode.trim();
  }

  public BigDecimal getBracketMin() {
    return bracketMin;
  }

  public void setBracketMin(BigDecimal bracketMin) {
    this.bracketMin = bracketMin;
  }

  public BigDecimal getBracketMax() {
    return bracketMax;
  }

  public void setBracketMax(BigDecimal bracketMax) {
    this.bracketMax = bracketMax;
  }

  public BigDecimal getTaxRate() {
    return taxRate;
  }

  public void setTaxRate(BigDecimal taxRate) {
    this.taxRate = taxRate;
  }

  public BigDecimal getBaseTax() {
    return baseTax;
  }

  public void setBaseTax(BigDecimal baseTax) {
    this.baseTax = baseTax;
  }

  public Boolean getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Boolean activeFlag) {
    this.activeFlag = activeFlag;
  }
}
