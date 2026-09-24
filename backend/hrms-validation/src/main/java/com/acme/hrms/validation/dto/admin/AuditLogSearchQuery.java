package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Query of GET /api/admin/audit-log (AUDIT_LOG search; to >= from, range <= 366 days). */
public class AuditLogSearchQuery {

  @Size(max = 60)
  @Pattern(regexp = "^[a-z][a-z0-9_]*$")
  @FieldMeta(trim = true, patternMessage = "tableName is the lower-case PostgreSQL table name")
  private String tableName;

  @Min(1)
  private Integer recordId;

  @AllowedValues({"INSERT", "UPDATE", "DELETE", "STATUS_CHANGE", "LOGIN", "LOGOUT"})
  private String actionType;

  @Size(max = 100)
  @FieldMeta(trim = true)
  private String changedBy;

  @FieldMeta private LocalDate from;

  @FieldMeta(
      ruleId = "audit.range",
      ruleValue = "from",
      ruleErrorCode = "VALIDATION_FAILED",
      ruleMessage = "to must be on or after from")
  private LocalDate to;

  @Min(0)
  private Integer page;

  @Min(1)
  @Max(AdminRules.PAGE_SIZE_MAX)
  private Integer size;

  @AssertTrue(message = "to must be on or after from")
  public boolean isRangeOrdered() {
    return from == null || to == null || !to.isBefore(from);
  }

  @AssertTrue(message = "range must not exceed 366 days")
  public boolean isRangeBounded() {
    return from == null
        || to == null
        || !to.isAfter(from.plusDays(AdminRules.AUDIT_RANGE_MAX_DAYS));
  }

  public String getTableName() {
    return tableName;
  }

  public void setTableName(String tableName) {
    this.tableName = tableName == null || tableName.isBlank() ? null : tableName.trim();
  }

  public Integer getRecordId() {
    return recordId;
  }

  public void setRecordId(Integer recordId) {
    this.recordId = recordId;
  }

  public String getActionType() {
    return actionType;
  }

  public void setActionType(String actionType) {
    this.actionType = actionType;
  }

  public String getChangedBy() {
    return changedBy;
  }

  public void setChangedBy(String changedBy) {
    this.changedBy = changedBy == null || changedBy.isBlank() ? null : changedBy.trim();
  }

  public LocalDate getFrom() {
    return from;
  }

  public void setFrom(LocalDate from) {
    this.from = from;
  }

  public LocalDate getTo() {
    return to;
  }

  public void setTo(LocalDate to) {
    this.to = to;
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
