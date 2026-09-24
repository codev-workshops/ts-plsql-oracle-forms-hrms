package com.acme.hrms.validation.dto.payroll;

import com.acme.hrms.validation.meta.AllowedValues;

/** Query of GET /api/payroll/periods/{periodId}/runs (PAYROLL_RUNS.CHK_RUN_STATUS filter). */
public class PayrollRunListQuery {

  @AllowedValues({"PENDING", "CALCULATING", "CALCULATED", "APPROVED", "PAID", "REVERSED", "ERROR"})
  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
