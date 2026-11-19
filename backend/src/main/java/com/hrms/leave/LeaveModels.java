package com.hrms.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Leave API payloads (openapi-v1.yaml, tag "Leave"). */
public final class LeaveModels {

    private LeaveModels() {
    }

    public record LeaveType(int leaveTypeId, String code, String name, boolean paid, boolean accrual,
                            BigDecimal accrualRate, BigDecimal maxBalance, BigDecimal carryoverMax,
                            boolean requiresApproval, int minTenureDays) { }

    public record LeaveBalance(int leaveTypeId, String leaveTypeCode, String leaveTypeName, int year,
                               BigDecimal openingBalance, BigDecimal accrued, BigDecimal used,
                               BigDecimal adjustment, BigDecimal pending, BigDecimal available,
                               BigDecimal carryoverFromPrev, LocalDate carryoverExpiryDate) { }

    public record LeaveRequest(long requestId, long empId, String employeeName, int leaveTypeId,
                               String leaveTypeCode, String leaveTypeName, LocalDate startDate, LocalDate endDate,
                               BigDecimal totalDays, boolean halfDay, String halfDayPeriod, String status,
                               String reason, Long approverEmpId, String approverName, LocalDateTime approvalDate,
                               String approvalComments, String cancelReason, LocalDateTime createdDate) { }

    public record SubmitLeaveRequest(
            @NotNull Integer leaveTypeId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            Boolean halfDay,
            @Pattern(regexp = "AM|PM") String halfDayPeriod,
            @Size(max = 4000) String reason) { }

    public record DecisionRequest(@Size(max = 4000) String comments) { }

    public record RejectRequest(@NotBlank @Size(max = 4000) String comments) { }

    public record CancelRequest(@NotBlank @Size(max = 4000) String reason) { }

    public record BusinessDays(LocalDate startDate, LocalDate endDate, int businessDays) { }

    public record AccrualRunRequest(LocalDate accrualDate) { }

    public record CarryoverRequest(@NotNull Integer year) { }

    public record InitializeBalancesRequest(@NotNull Long empId, @NotNull Integer year) { }

    public record BatchResult(String operation, int rowsAffected) { }
}
