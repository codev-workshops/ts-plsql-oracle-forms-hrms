package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST/PUT /api/admin/departments (DEPARTMENTS; PKG_EMPLOYEE.validate_dept -20003 on the
 * parent, -20605 cycle, -20604 location). Codes are immutable after create (-20601).
 */
public class DepartmentRequest {

  @NotBlank
  @Size(min = 1, max = 20)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Department code is required",
      patternMessage = AdminRules.CODE_MESSAGE)
  private String deptCode;

  @NotBlank
  @Size(min = 1, max = 100)
  @FieldMeta(trim = true, requiredMessage = "Department name is required")
  private String deptName;

  @Min(1)
  private Integer parentDeptId;

  @Size(max = 20)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(trim = true, patternMessage = AdminRules.CODE_MESSAGE)
  private String costCenter;

  @Min(1)
  private Integer managerEmpId;

  @Size(max = 10)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(trim = true, patternMessage = AdminRules.CODE_MESSAGE)
  private String locationCode;

  @FieldMeta private Boolean activeFlag;

  public String getDeptCode() {
    return deptCode;
  }

  public void setDeptCode(String deptCode) {
    this.deptCode = deptCode == null || deptCode.isBlank() ? null : deptCode.trim();
  }

  public String getDeptName() {
    return deptName;
  }

  public void setDeptName(String deptName) {
    this.deptName = deptName == null || deptName.isBlank() ? null : deptName.trim();
  }

  public Integer getParentDeptId() {
    return parentDeptId;
  }

  public void setParentDeptId(Integer parentDeptId) {
    this.parentDeptId = parentDeptId;
  }

  public String getCostCenter() {
    return costCenter;
  }

  public void setCostCenter(String costCenter) {
    this.costCenter = costCenter == null || costCenter.isBlank() ? null : costCenter.trim();
  }

  public Integer getManagerEmpId() {
    return managerEmpId;
  }

  public void setManagerEmpId(Integer managerEmpId) {
    this.managerEmpId = managerEmpId;
  }

  public String getLocationCode() {
    return locationCode;
  }

  public void setLocationCode(String locationCode) {
    this.locationCode = locationCode == null || locationCode.isBlank() ? null : locationCode.trim();
  }

  public Boolean getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Boolean activeFlag) {
    this.activeFlag = activeFlag;
  }
}
