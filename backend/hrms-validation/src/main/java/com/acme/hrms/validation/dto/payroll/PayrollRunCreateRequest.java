package com.acme.hrms.validation.dto.payroll;

import com.acme.hrms.validation.dto.employee.StrictRequest;
import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/payroll/periods/{periodId}/runs. PKG_PAYROLL.create_payroll_run defaulted
 * p_run_type to REGULAR; the REST body makes the choice explicit (PAYROLL_RUNS.CHK_RUN_TYPE).
 * {@code additionalProperties: false}.
 */
public class PayrollRunCreateRequest extends StrictRequest {

  @NotNull
  @AllowedValues({"REGULAR", "SUPPLEMENTAL", "BONUS", "FINAL"})
  @FieldMeta(requiredMessage = "runType is required")
  private String runType;

  public String getRunType() {
    return runType;
  }

  public void setRunType(String runType) {
    this.runType = runType;
  }
}
