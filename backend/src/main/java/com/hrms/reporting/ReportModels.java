package com.hrms.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Response records of the reporting API (contract amendment v1.4, OQ-03). */
public final class ReportModels {

    private ReportModels() {
    }

    public record ReportDescriptor(String id, String title, String path, String sourceView, String permission,
            boolean available) {
    }

    /** VW_PENDING_APPROVALS (VW-006). */
    public record PendingApproval(String approvalType, long itemId, Long approverId, String requestorName,
            String itemDescription, OffsetDateTime requestDate, String details) {
    }

    /** VW_EMPLOYEE_COMPENSATION (VW-003). */
    public record EmployeeCompensation(long empId, String empNumber, String empName, String deptName,
            String jobTitle, String gradeName, BigDecimal baseSalary, BigDecimal gradeMin, BigDecimal gradeMax,
            BigDecimal gradeMidpoint, BigDecimal compaRatio, LocalDate salaryEffectiveDate,
            String lastChangeReason, BigDecimal lastChangePct) {
    }

    /**
     * VW_LEAVE_SUMMARY (VW-004). {@code available} is net of pending (= leave_balances.available, what a new
     * request may use); {@code availableBeforePending} is the legacy view's AVAILABLE, which ignores pending
     * (DEF-23, OQ-14 provisional).
     */
    public record LeaveSummaryRow(long empId, String empNumber, String empName, String deptName,
            String leaveTypeName, int calendarYear, BigDecimal openingBalance, BigDecimal accrued, BigDecimal used,
            BigDecimal adjustment, BigDecimal pending, BigDecimal available, BigDecimal availableBeforePending,
            BigDecimal utilizationPct) {
    }

    /** VW_ORG_HIERARCHY (VW-002) and PKG_EMPLOYEE.get_org_chart (PKG-E07). */
    public record OrgNode(long empId, String empNumber, String empName, Long managerEmpId, long deptId,
            int orgLevel, String orgPath, boolean leaf) {
    }

    /** VW_PAYROLL_LATEST (VW-005). */
    public record PayrollLatest(long empId, String empNumber, String empName, String periodName,
            BigDecimal grossPay, BigDecimal totalTaxes, BigDecimal totalDeductions, BigDecimal netPay) {
    }
}
