package com.acme.hrms.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.lang.Nullable;

/** Wire representations of contracts/p2-leave/openapi.yaml (components.schemas). */
public final class LeaveDtos {

  private LeaveDtos() {}

  public record LeaveRequest(
      long requestId,
      long empId,
      String empName,
      int leaveTypeId,
      String leaveTypeCode,
      String leaveTypeName,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal totalDays,
      boolean halfDay,
      @Nullable String halfDayPeriod,
      String status,
      @Nullable String reason,
      @Nullable Long approverEmpId,
      @Nullable String approverName,
      @Nullable LocalDateTime approvalDate,
      @Nullable String approvalComments,
      LocalDateTime createdDate,
      @Nullable LocalDateTime modifiedDate) {}

  public record PageOfLeaveRequest(
      List<LeaveRequest> content, int page, int size, long totalElements, int totalPages) {}

  public record LeaveBalance(
      long balanceId,
      int leaveTypeId,
      String leaveTypeCode,
      String leaveTypeName,
      int calendarYear,
      BigDecimal openingBalance,
      BigDecimal accrued,
      BigDecimal used,
      BigDecimal adjustment,
      BigDecimal pending,
      BigDecimal carryoverFromPrev,
      BigDecimal available) {}

  public record BusinessDays(
      LocalDate start, LocalDate end, int businessDays, List<ObservedHoliday> holidays) {}

  public record ObservedHoliday(
      String holidayName, LocalDate holidayDate, LocalDate observedDate) {}

  public record PendingLeaveApproval(
      long requestId,
      long empId,
      String empNumber,
      String empName,
      String leaveTypeName,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal totalDays,
      boolean halfDay,
      @Nullable String halfDayPeriod,
      @Nullable String reason,
      LocalDateTime createdDate) {}

  public record TeamCalendarEntry(
      long requestId,
      long empId,
      String empName,
      String leaveTypeName,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal totalDays,
      boolean halfDay,
      @Nullable String halfDayPeriod,
      String status) {}
}
