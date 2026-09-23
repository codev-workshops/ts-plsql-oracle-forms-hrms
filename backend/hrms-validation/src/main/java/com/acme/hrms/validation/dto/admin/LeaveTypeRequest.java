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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of POST/PUT /api/admin/leave-types (LEAVE_TYPES; CHK_ACCRUAL_FREQ; carryoverMax <=
 * maxBalance -> -20603).
 */
public class LeaveTypeRequest {

  @NotBlank
  @Size(min = 1, max = 20)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Leave type code is required",
      patternMessage = AdminRules.CODE_MESSAGE)
  private String leaveTypeCode;

  @NotBlank
  @Size(min = 1, max = 50)
  @FieldMeta(trim = true, requiredMessage = "Leave type name is required")
  private String leaveTypeName;

  @FieldMeta private Boolean paidFlag;

  @FieldMeta private Boolean accrualFlag;

  @DecimalMin(value = "0.00")
  @DecimalMax(value = "9999.99")
  @Digits(integer = 4, fraction = 2)
  private BigDecimal accrualRate;

  @AllowedValues({"MONTHLY", "BIWEEKLY", "ANNUAL"})
  private String accrualFrequency;

  @DecimalMin(value = "0.00")
  @DecimalMax(value = "9999.99")
  @Digits(integer = 4, fraction = 2)
  private BigDecimal maxBalance;

  @DecimalMin(value = "0.00")
  @DecimalMax(value = "9999.99")
  @Digits(integer = 4, fraction = 2)
  private BigDecimal carryoverMax;

  @Min(0)
  @Max(12)
  private Integer carryoverExpiry;

  @Min(0)
  @Max(3650)
  private Integer minTenureDays;

  @FieldMeta private Boolean requiresApproval;

  @FieldMeta private Boolean requiresDocument;

  @FieldMeta private Boolean activeFlag;

  @AssertTrue(message = "accrualRate and accrualFrequency are required when accrualFlag is true")
  public boolean isAccrualPolicyComplete() {
    return !Boolean.TRUE.equals(accrualFlag) || (accrualRate != null && accrualFrequency != null);
  }

  @AssertTrue(message = "carryoverMax must not exceed maxBalance")
  public boolean isCarryoverWithinMax() {
    return carryoverMax == null || maxBalance == null || carryoverMax.compareTo(maxBalance) <= 0;
  }

  public String getLeaveTypeCode() {
    return leaveTypeCode;
  }

  public void setLeaveTypeCode(String leaveTypeCode) {
    this.leaveTypeCode =
        leaveTypeCode == null || leaveTypeCode.isBlank() ? null : leaveTypeCode.trim();
  }

  public String getLeaveTypeName() {
    return leaveTypeName;
  }

  public void setLeaveTypeName(String leaveTypeName) {
    this.leaveTypeName =
        leaveTypeName == null || leaveTypeName.isBlank() ? null : leaveTypeName.trim();
  }

  public Boolean getPaidFlag() {
    return paidFlag;
  }

  public void setPaidFlag(Boolean paidFlag) {
    this.paidFlag = paidFlag;
  }

  public Boolean getAccrualFlag() {
    return accrualFlag;
  }

  public void setAccrualFlag(Boolean accrualFlag) {
    this.accrualFlag = accrualFlag;
  }

  public BigDecimal getAccrualRate() {
    return accrualRate;
  }

  public void setAccrualRate(BigDecimal accrualRate) {
    this.accrualRate = accrualRate;
  }

  public String getAccrualFrequency() {
    return accrualFrequency;
  }

  public void setAccrualFrequency(String accrualFrequency) {
    this.accrualFrequency = accrualFrequency;
  }

  public BigDecimal getMaxBalance() {
    return maxBalance;
  }

  public void setMaxBalance(BigDecimal maxBalance) {
    this.maxBalance = maxBalance;
  }

  public BigDecimal getCarryoverMax() {
    return carryoverMax;
  }

  public void setCarryoverMax(BigDecimal carryoverMax) {
    this.carryoverMax = carryoverMax;
  }

  public Integer getCarryoverExpiry() {
    return carryoverExpiry;
  }

  public void setCarryoverExpiry(Integer carryoverExpiry) {
    this.carryoverExpiry = carryoverExpiry;
  }

  public Integer getMinTenureDays() {
    return minTenureDays;
  }

  public void setMinTenureDays(Integer minTenureDays) {
    this.minTenureDays = minTenureDays;
  }

  public Boolean getRequiresApproval() {
    return requiresApproval;
  }

  public void setRequiresApproval(Boolean requiresApproval) {
    this.requiresApproval = requiresApproval;
  }

  public Boolean getRequiresDocument() {
    return requiresDocument;
  }

  public void setRequiresDocument(Boolean requiresDocument) {
    this.requiresDocument = requiresDocument;
  }

  public Boolean getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Boolean activeFlag) {
    this.activeFlag = activeFlag;
  }
}
