package com.acme.hrms.validation.dto.integration;

import com.acme.hrms.validation.dto.admin.AdminRules;
import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;

/** Query of GET /api/integration/files. */
public class IntegrationFileListQuery {

  @AllowedValues({"GL_JOURNAL", "BENEFITS_FEED", "TIME_ATTENDANCE"})
  private String feed;

  @AllowedValues({"SUCCESS", "FAILED", "STAGED"})
  private String status;

  @FieldMeta private LocalDate from;

  @FieldMeta(
      ruleId = "files.range",
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

  public String getFeed() {
    return feed;
  }

  public void setFeed(String feed) {
    this.feed = feed;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
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
