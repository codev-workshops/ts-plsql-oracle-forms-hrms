package com.acme.hrms.validation.dto.reporting;

import com.acme.hrms.validation.dto.admin.AdminRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Query of GET /api/reports/payroll-latest[.csv] (VW_PAYROLL_LATEST + payroll_summary_report). */
public class PayrollLatestQuery {

  @Min(1)
  private Integer periodId;

  @Min(1)
  private Integer deptId;

  @Min(0)
  private Integer page;

  @Min(1)
  @Max(AdminRules.REPORT_PAGE_SIZE_MAX)
  private Integer size;

  public Integer getPeriodId() {
    return periodId;
  }

  public void setPeriodId(Integer periodId) {
    this.periodId = periodId;
  }

  public Integer getDeptId() {
    return deptId;
  }

  public void setDeptId(Integer deptId) {
    this.deptId = deptId;
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
