package com.acme.hrms.validation.dto.leave;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Query of GET /api/leave/business-days?start&end (and, by shape, /team-calendar?from&to). */
public class BusinessDaysQuery {

  public static final int MAX_RANGE_DAYS = 366;

  @NotNull
  @FieldMeta(requiredMessage = "Start date is required")
  private LocalDate start;

  @NotNull
  @FieldMeta(
      requiredMessage = "End date is required",
      formatMessage = "Start date must be before or equal to end date",
      ruleId = "leave.dateOrder",
      ruleValue = "start",
      ruleErrorCode = "-20210",
      ruleMessage = "Start date must be before or equal to end date")
  private LocalDate end;

  public LocalDate getStart() {
    return start;
  }

  public void setStart(LocalDate start) {
    this.start = start;
  }

  public LocalDate getEnd() {
    return end;
  }

  public void setEnd(LocalDate end) {
    this.end = end;
  }

  @AssertTrue(message = "Start date must be before or equal to end date")
  public boolean isDateOrderValid() {
    return start == null || end == null || !start.isAfter(end);
  }

  @AssertTrue(message = "Range may not exceed 366 days")
  public boolean isRangeBounded() {
    return start == null || end == null || ChronoUnit.DAYS.between(start, end) <= MAX_RANGE_DAYS;
  }
}
