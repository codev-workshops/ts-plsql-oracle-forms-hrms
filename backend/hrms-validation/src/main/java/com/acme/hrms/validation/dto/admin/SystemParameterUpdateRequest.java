package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/admin/system-parameters/{paramId} (group, code and dataType are immutable;
 * -20606 when EDITABLE_FLAG = N).
 */
public class SystemParameterUpdateRequest {

  @NotBlank
  @Size(min = 1, max = 4000)
  @FieldMeta(trim = true, requiredMessage = "Parameter value is required")
  private String paramValue;

  @Size(max = 200)
  @FieldMeta(trim = true)
  private String paramDescription;

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
}
