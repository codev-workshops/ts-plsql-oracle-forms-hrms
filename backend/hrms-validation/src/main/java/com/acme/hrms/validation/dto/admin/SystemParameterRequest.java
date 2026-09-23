package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/admin/system-parameters (SYSTEM_PARAMETERS; (group, code) unique -> -20601;
 * value must parse as dataType -> -20603).
 */
public class SystemParameterRequest {

  @NotBlank
  @Size(min = 1, max = 50)
  @Pattern(regexp = AdminRules.PARAM_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Parameter group is required",
      patternMessage = AdminRules.PARAM_MESSAGE)
  private String paramGroup;

  @NotBlank
  @Size(min = 1, max = 50)
  @Pattern(regexp = AdminRules.PARAM_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Parameter code is required",
      patternMessage = AdminRules.PARAM_MESSAGE)
  private String paramCode;

  @NotBlank
  @Size(min = 1, max = 4000)
  @FieldMeta(trim = true, requiredMessage = "Parameter value is required")
  private String paramValue;

  @Size(max = 200)
  @FieldMeta(trim = true)
  private String paramDescription;

  @NotNull
  @AllowedValues({"VARCHAR2", "NUMBER", "DATE", "BOOLEAN"})
  @FieldMeta(requiredMessage = "Data type is required")
  private String dataType;

  @FieldMeta private Boolean editableFlag;

  public String getParamGroup() {
    return paramGroup;
  }

  public void setParamGroup(String paramGroup) {
    this.paramGroup = paramGroup == null || paramGroup.isBlank() ? null : paramGroup.trim();
  }

  public String getParamCode() {
    return paramCode;
  }

  public void setParamCode(String paramCode) {
    this.paramCode = paramCode == null || paramCode.isBlank() ? null : paramCode.trim();
  }

  public String getParamValue() {
    return paramValue;
  }

  public void setParamValue(String paramValue) {
    this.paramValue = paramValue == null || paramValue.isBlank() ? null : paramValue.trim();
  }

  public String getParamDescription() {
    return paramDescription;
  }

  public void setParamDescription(String paramDescription) {
    this.paramDescription =
        paramDescription == null || paramDescription.isBlank() ? null : paramDescription.trim();
  }

  public String getDataType() {
    return dataType;
  }

  public void setDataType(String dataType) {
    this.dataType = dataType;
  }

  public Boolean getEditableFlag() {
    return editableFlag;
  }

  public void setEditableFlag(Boolean editableFlag) {
    this.editableFlag = editableFlag;
  }
}
