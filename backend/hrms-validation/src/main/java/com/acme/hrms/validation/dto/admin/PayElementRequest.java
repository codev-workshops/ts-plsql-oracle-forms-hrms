package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of POST/PUT /api/admin/pay-elements (PAY_ELEMENTS; CHK_ELEM_TYPE, CHK_CALC_TYPE). Value
 * rules -> -20603; elementCode immutable -> -20601; reserved ids 0, 1, 100-103 and TAX on create ->
 * -20607 (server side, PayrollConstants).
 */
public class PayElementRequest {

  @NotBlank
  @Size(min = 1, max = 30)
  @Pattern(regexp = AdminRules.PARAM_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Element code is required",
      patternMessage = AdminRules.PARAM_MESSAGE)
  private String elementCode;

  @NotBlank
  @Size(min = 1, max = 100)
  @FieldMeta(trim = true, requiredMessage = "Element name is required")
  private String elementName;

  @NotNull
  @AllowedValues({"EARNING", "DEDUCTION", "TAX", "BENEFIT", "REIMBURSEMENT"})
  @FieldMeta(requiredMessage = "Element type is required")
  private String elementType;

  @NotNull
  @AllowedValues({"FLAT", "PERCENTAGE", "HOURS", "FORMULA"})
  @FieldMeta(requiredMessage = "Calculation type is required")
  private String calculationType;

  @DecimalMin(value = "0.00")
  @Digits(integer = 10, fraction = 2)
  private BigDecimal defaultAmount;

  @DecimalMin(value = "0.00", inclusive = false)
  @DecimalMax(value = "100.00")
  @Digits(integer = 3, fraction = 2)
  private BigDecimal defaultPercentage;

  @FieldMeta private Boolean taxableFlag;

  @FieldMeta private Boolean pretaxFlag;

  @FieldMeta private Boolean employerPaid;

  @Size(max = 30)
  @Pattern(regexp = AdminRules.GL_ACCOUNT_PATTERN)
  @FieldMeta(trim = true, patternMessage = AdminRules.GL_ACCOUNT_MESSAGE)
  private String glAccountCode;

  @Min(0)
  @Max(9999)
  private Integer priorityOrder;

  @FieldMeta private Boolean activeFlag;

  @AssertTrue(
      message =
          "FLAT/HOURS need defaultAmount only, PERCENTAGE needs defaultPercentage only,"
              + " FORMULA needs neither")
  public boolean isDefaultsConsistentWithCalculationType() {
    if (calculationType == null) {
      return true;
    }
    return switch (calculationType) {
      case "FLAT", "HOURS" -> defaultAmount != null && defaultPercentage == null;
      case "PERCENTAGE" -> defaultPercentage != null && defaultAmount == null;
      case "FORMULA" -> defaultAmount == null && defaultPercentage == null;
      default -> true;
    };
  }

  @AssertTrue(message = "pretaxFlag is only allowed on DEDUCTION elements")
  public boolean isPretaxOnlyOnDeduction() {
    return !Boolean.TRUE.equals(pretaxFlag) || "DEDUCTION".equals(elementType);
  }

  public String getElementCode() {
    return elementCode;
  }

  public void setElementCode(String elementCode) {
    this.elementCode = elementCode == null || elementCode.isBlank() ? null : elementCode.trim();
  }

  public String getElementName() {
    return elementName;
  }

  public void setElementName(String elementName) {
    this.elementName = elementName == null || elementName.isBlank() ? null : elementName.trim();
  }

  public String getElementType() {
    return elementType;
  }

  public void setElementType(String elementType) {
    this.elementType = elementType;
  }

  public String getCalculationType() {
    return calculationType;
  }

  public void setCalculationType(String calculationType) {
    this.calculationType = calculationType;
  }

  public BigDecimal getDefaultAmount() {
    return defaultAmount;
  }

  public void setDefaultAmount(BigDecimal defaultAmount) {
    this.defaultAmount = defaultAmount;
  }

  public BigDecimal getDefaultPercentage() {
    return defaultPercentage;
  }

  public void setDefaultPercentage(BigDecimal defaultPercentage) {
    this.defaultPercentage = defaultPercentage;
  }

  public Boolean getTaxableFlag() {
    return taxableFlag;
  }

  public void setTaxableFlag(Boolean taxableFlag) {
    this.taxableFlag = taxableFlag;
  }

  public Boolean getPretaxFlag() {
    return pretaxFlag;
  }

  public void setPretaxFlag(Boolean pretaxFlag) {
    this.pretaxFlag = pretaxFlag;
  }

  public Boolean getEmployerPaid() {
    return employerPaid;
  }

  public void setEmployerPaid(Boolean employerPaid) {
    this.employerPaid = employerPaid;
  }

  public String getGlAccountCode() {
    return glAccountCode;
  }

  public void setGlAccountCode(String glAccountCode) {
    this.glAccountCode =
        glAccountCode == null || glAccountCode.isBlank() ? null : glAccountCode.trim();
  }

  public Integer getPriorityOrder() {
    return priorityOrder;
  }

  public void setPriorityOrder(Integer priorityOrder) {
    this.priorityOrder = priorityOrder;
  }

  public Boolean getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Boolean activeFlag) {
    this.activeFlag = activeFlag;
  }
}
