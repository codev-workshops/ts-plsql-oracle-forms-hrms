package com.acme.hrms.validation.dto.reporting;

import com.acme.hrms.validation.dto.admin.AdminRules;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Query of GET /api/reports/employee-directory[.csv] (VW_ACTIVE_EMPLOYEES + headcount_report
 * filters).
 */
public class EmployeeDirectoryQuery {

  @FieldMeta private LocalDate asOf;

  @Min(1)
  private Integer deptId;

  @Size(max = 10)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(trim = true, patternMessage = AdminRules.CODE_MESSAGE)
  private String locationCode;

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

  public String getLocationCode() {
    return locationCode;
  }

  public void setLocationCode(String locationCode) {
    this.locationCode = locationCode == null || locationCode.isBlank() ? null : locationCode.trim();
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
