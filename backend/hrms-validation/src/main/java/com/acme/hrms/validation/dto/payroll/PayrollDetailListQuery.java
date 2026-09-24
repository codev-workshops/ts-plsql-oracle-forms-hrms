package com.acme.hrms.validation.dto.payroll;

import com.acme.hrms.validation.meta.AllowedValues;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Query of GET /api/payroll/runs/{runId}/details. */
public class PayrollDetailListQuery {

  @Min(1)
  private Integer empId;

  @AllowedValues({"CALCULATED", "ERROR", "REVERSED"})
  private String status;

  @Min(0)
  private Integer page;

  @Min(1)
  @Max(500)
  private Integer size;

  public Integer getEmpId() {
    return empId;
  }

  public void setEmpId(Integer empId) {
    this.empId = empId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Integer getPage() {
    return page;
  }

  public void setPage(Integer page) {
    this.page = page;
  }

  public Integer getSize() {
    return size;
  }

  public void setSize(Integer size) {
    this.size = size;
  }
}
