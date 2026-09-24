package com.acme.hrms.validation.dto.reporting;

import com.acme.hrms.validation.dto.admin.AdminRules;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;

/**
 * Query of GET /api/reports/leave-summary[.csv] (VW_LEAVE_SUMMARY with VAL-05 fixed +
 * leave_utilization_report).
 */
public class LeaveSummaryQuery {

  @FieldMeta private LocalDate asOf;

  @Min(2000)
  @Max(2099)
  private Integer year;

  @Min(1)
  private Integer deptId;

  @Min(1)
  private Integer leaveTypeId;

  @Min(0)
  private Integer page;

  @Min(1)
  @Max(AdminRules.REPORT_PAGE_SIZE_MAX)
  private Integer size;

  public LocalDate getAsOf() {
    return asOf;
  }

  public void setAsOf(LocalDate asOf) {
    this.asOf = asOf;
  }

  public Integer getYear() {
    return year;
  }

  public void setYear(Integer year) {
    this.year = year;
  }

  public Integer getDeptId() {
    return deptId;
  }

  public void setDeptId(Integer deptId) {
    this.deptId = deptId;
  }

  public Integer getLeaveTypeId() {
    return leaveTypeId;
  }

  public void setLeaveTypeId(Integer leaveTypeId) {
    this.leaveTypeId = leaveTypeId;
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
