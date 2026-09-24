package com.acme.hrms.validation.dto.reporting;

import com.acme.hrms.validation.dto.admin.AdminRules;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;

/**
 * Query of GET /api/reports/employee-compensation[.csv] (VW_EMPLOYEE_COMPENSATION +
 * compensation_summary).
 */
public class EmployeeCompensationQuery {

  @FieldMeta private LocalDate asOf;

  @Min(1)
  private Integer deptId;

  @Min(1)
  private Integer gradeId;

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

  public Integer getDeptId() {
    return deptId;
  }

  public void setDeptId(Integer deptId) {
    this.deptId = deptId;
  }

  public Integer getGradeId() {
    return gradeId;
  }

  public void setGradeId(Integer gradeId) {
    this.gradeId = gradeId;
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
