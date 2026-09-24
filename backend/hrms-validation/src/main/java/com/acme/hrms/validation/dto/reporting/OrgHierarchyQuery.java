package com.acme.hrms.validation.dto.reporting;

import com.acme.hrms.validation.dto.admin.AdminRules;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;

/**
 * Query of GET /api/reports/org-hierarchy[.csv] (VW_ORG_HIERARCHY; rootEmpId is a subject, -20001).
 */
public class OrgHierarchyQuery {

  @FieldMeta private LocalDate asOf;

  @Min(1)
  private Integer rootEmpId;

  @Min(1)
  @Max(20)
  private Integer maxLevel;

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

  public Integer getRootEmpId() {
    return rootEmpId;
  }

  public void setRootEmpId(Integer rootEmpId) {
    this.rootEmpId = rootEmpId;
  }

  public Integer getMaxLevel() {
    return maxLevel;
  }

  public void setMaxLevel(Integer maxLevel) {
    this.maxLevel = maxLevel;
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
