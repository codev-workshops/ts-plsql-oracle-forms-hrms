package com.acme.hrms.validation.dto.leave;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Body of POST /api/leave/requests (contracts/p2-leave/openapi.yaml). */
public class LeaveRequestCreateRequest {

  /** PKG_LEAVE.submit_leave_request: {@code p_start_date < TRUNC(SYSDATE) - 5} raises -20211. */
  public static final int MAX_DAYS_IN_PAST = 5;

  @NotNull
  @Min(1)
  @FieldMeta(requiredMessage = "Leave type is required")
  private Integer leaveTypeId;

  @NotNull
  @FieldMeta(
      requiredMessage = "Start date is required",
      ruleId = "leave.pastLimit",
      ruleValue = "" + MAX_DAYS_IN_PAST,
      ruleErrorCode = "-20211",
      ruleMessage = "Cannot submit leave requests more than 5 days in the past")
  private LocalDate startDate;

  @NotNull
  @FieldMeta(
      requiredMessage = "End date is required",
      formatMessage = "Start date must be before or equal to end date",
      ruleId = "leave.dateOrder",
      ruleValue = "startDate",
      ruleErrorCode = "-20210",
      ruleMessage = "Start date must be before or equal to end date")
  private LocalDate endDate;

  @FieldMeta(formatMessage = "A half day must start and end on the same date")
  private Boolean halfDay;

  @AllowedValues({"AM", "PM"})
  @FieldMeta(requiredMessage = "Select AM or PM for a half day")
  private String halfDayPeriod;

  @Size(max = LeaveText.MAX)
  @FieldMeta(trim = true)
  private String reason;

  public Integer getLeaveTypeId() {
    return leaveTypeId;
  }

  public void setLeaveTypeId(Integer leaveTypeId) {
    this.leaveTypeId = leaveTypeId;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public void setEndDate(LocalDate endDate) {
    this.endDate = endDate;
  }

  public boolean isHalfDay() {
    return Boolean.TRUE.equals(halfDay);
  }

  public Boolean getHalfDay() {
    return halfDay;
  }

  public void setHalfDay(Boolean halfDay) {
    this.halfDay = halfDay;
  }

  public String getHalfDayPeriod() {
    return halfDayPeriod;
  }

  public void setHalfDayPeriod(String halfDayPeriod) {
    this.halfDayPeriod = LeaveText.blankToNull(halfDayPeriod);
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = LeaveText.blankToNull(reason);
  }

  /**
   * -20210 as a Bean Validation rule so the API reports it with the legacy code (error-codes.md
   * §1).
   */
  @AssertTrue(message = "Start date must be before or equal to end date")
  public boolean isDateOrderValid() {
    return startDate == null || endDate == null || !startDate.isAfter(endDate);
  }

  /** TIGHTEN-01: a half day is a single date. */
  @AssertTrue(message = "A half day must start and end on the same date")
  public boolean isHalfDaySingleDate() {
    return !isHalfDay() || startDate == null || endDate == null || startDate.equals(endDate);
  }

  /** TIGHTEN-03: halfDayPeriod present iff halfDay. */
  @AssertTrue(message = "Select AM or PM for a half day")
  public boolean isHalfDayPeriodConsistent() {
    return isHalfDay() == (halfDayPeriod != null);
  }
}
