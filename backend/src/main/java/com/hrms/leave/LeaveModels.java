package com.hrms.leave;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Request and response records of the leave API (contract v1). */
public final class LeaveModels {

    private LeaveModels() {
    }

    public enum LeaveStatus { PENDING, APPROVED, REJECTED, CANCELLED, TAKEN }

    public enum HalfDayPeriod { AM, PM }

    public record LeaveType(long leaveTypeId, String leaveTypeCode, String leaveTypeName, boolean paid,
            boolean accrual, BigDecimal accrualRate, String accrualFrequency, BigDecimal maxBalance,
            Integer minTenureDays, boolean requiresApproval, boolean requiresDocument) {
    }

    public record LeaveBalance(long leaveTypeId, String leaveTypeCode, String leaveTypeName, int calendarYear,
            BigDecimal openingBalance, BigDecimal accrued, BigDecimal used, BigDecimal adjustment,
            BigDecimal pending, BigDecimal available, BigDecimal carryoverFromPrev, LocalDate carryoverExpiryDate) {
    }

    public record LeaveRequest(long requestId, long empId, String employeeName, long leaveTypeId,
            String leaveTypeCode, String leaveTypeName, LocalDate startDate, LocalDate endDate, BigDecimal totalDays,
            boolean halfDay, HalfDayPeriod halfDayPeriod, LeaveStatus status, String reason, Long approverEmpId,
            OffsetDateTime approvalDate, String approvalComments, String cancelReason, OffsetDateTime cancelledDate,
            OffsetDateTime createdDate) {
    }

    public record SubmitLeaveRequest(@NotNull Long leaveTypeId, @NotNull LocalDate startDate,
            @NotNull LocalDate endDate, Boolean halfDay, HalfDayPeriod halfDayPeriod,
            @Size(max = 4000) String reason) {
    }

    public record LeaveDecision(@Size(max = 4000) String comments) {
    }

    public record CancelLeaveRequest(@Size(max = 4000) String reason) {
    }

    public record TeamCalendarEntry(long requestId, long empId, String employeeName, String leaveTypeCode,
            String leaveTypeName, LocalDate startDate, LocalDate endDate, BigDecimal totalDays, LeaveStatus status,
            boolean halfDay, HalfDayPeriod halfDayPeriod) {
    }

    public record AccrualRunRequest(@NotNull LocalDate accrualDate) {
    }

    public record AccrualRun(long runId, LocalDate accrualPeriod, LocalDate accrualDate, int employeesProcessed,
            int entriesCreated, BigDecimal totalAccrued, String createdBy, OffsetDateTime createdDate) {
    }
}
