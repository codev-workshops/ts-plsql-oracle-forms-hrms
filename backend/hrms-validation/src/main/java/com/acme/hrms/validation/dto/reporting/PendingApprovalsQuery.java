package com.acme.hrms.validation.dto.reporting;

import com.acme.hrms.validation.dto.admin.AdminRules;
import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;

/**
 * Query of GET /api/reports/pending-approvals[.csv] (VW_PENDING_APPROVALS; mine=true scopes to
 * jwt.empId).
 */
public class PendingApprovalsQuery {

  @FieldMeta private LocalDate asOf;

  @AllowedValues({"LEAVE", "REVIEW"})
  private String itemType;

  @FieldMeta private Boolean mine;

  @Min(1)
  private Integer deptId;

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

  public String getItemType() {
    return itemType;
  }

  public void setItemType(String itemType) {
    this.itemType = itemType;
  }

  public Boolean getMine() {
    return mine;
  }

  public void setMine(Boolean mine) {
    this.mine = mine;
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
