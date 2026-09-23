package com.acme.hrms.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Response shapes of contracts/p5-reporting-decommission/openapi.yaml. Record component order is
 * the JSON property order and therefore the CSV header order.
 */
public final class ReportingDtos {

  private ReportingDtos() {}

  /** {@code Money}: exactly two decimals. */
  public static String money(@Nullable BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** {@code Days}: two decimals. */
  public static String days(@Nullable BigDecimal v) {
    return money(v);
  }

  /** {@code Rate}: four decimals ({@code ROUND(x, 4)}). */
  public static String rate(@Nullable BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  /** {@code Years}: {@code TRUNC(months / 12, 1)} - one decimal, truncated. */
  public static String years(@Nullable BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(1, RoundingMode.DOWN).toPlainString();
  }

  /** {@code Percent}: {@code ROUND(x, 1)}; null propagates (Oracle NULL on a 0 denominator). */
  @Nullable
  public static String percent(@Nullable BigDecimal v) {
    return v == null ? null : v.setScale(1, RoundingMode.HALF_UP).toPlainString();
  }

  public record PageMeta(int page, int size, long totalElements, int totalPages) {
    public static PageMeta of(int page, int size, long total) {
      return new PageMeta(page, size, total, (int) Math.ceil(total / (double) size));
    }
  }

  // ---- employee directory
  public record EmployeeDirectoryRow(
      long empId,
      String empNumber,
      String firstName,
      String lastName,
      String fullName,
      @Nullable String email,
      @Nullable String phoneWork,
      LocalDate hireDate,
      String tenureYears,
      long deptId,
      String deptCode,
      String deptName,
      @Nullable String costCenter,
      long jobId,
      String jobCode,
      String jobTitle,
      @Nullable String jobFamily,
      int gradeId,
      String gradeCode,
      String gradeName,
      @Nullable String locationCode,
      @Nullable String locationName,
      @Nullable String city,
      @Nullable String stateProvince,
      @Nullable Long managerEmpId,
      @Nullable String managerName) {}

  public record HeadcountByDepartment(
      long deptId,
      String deptCode,
      String deptName,
      @Nullable String locationCode,
      int headcount,
      String avgTenureYears) {}

  public record DirectorySummary(
      int totalHeadcount, List<HeadcountByDepartment> headcountByDepartment) {}

  public record EmployeeDirectoryPage(
      LocalDate asOf,
      List<EmployeeDirectoryRow> content,
      PageMeta page,
      DirectorySummary summary) {}

  // ---- org hierarchy
  public record OrgHierarchyRow(
      long empId,
      String empNumber,
      String fullName,
      String jobTitle,
      String deptName,
      @Nullable Long managerEmpId,
      @Nullable String managerName,
      int orgLevel,
      String orgPath,
      boolean isLeaf,
      int directReports,
      boolean cycle) {}

  public record OrgHierarchyPage(LocalDate asOf, List<OrgHierarchyRow> content, PageMeta page) {}

  // ---- employee compensation
  public record EmployeeCompensationRow(
      long empId,
      String empNumber,
      String fullName,
      long deptId,
      String deptName,
      String jobTitle,
      int gradeId,
      String gradeCode,
      String baseSalary,
      String currencyCode,
      String payFrequency,
      LocalDate effectiveDate,
      String yearsInGrade,
      String minSalary,
      String maxSalary,
      String compaRatio) {}

  public record CompensationByDepartment(
      long deptId,
      String deptName,
      int headcount,
      String avgSalary,
      String minSalary,
      String maxSalary,
      String totalPayroll) {}

  public record CompensationSummary(List<CompensationByDepartment> byDepartment) {}

  public record EmployeeCompensationPage(
      LocalDate asOf,
      List<EmployeeCompensationRow> content,
      PageMeta page,
      CompensationSummary summary) {}

  // ---- leave summary
  public record LeaveSummaryRow(
      long empId,
      String empNumber,
      String empName,
      String deptName,
      int leaveTypeId,
      String leaveTypeName,
      int calendarYear,
      String openingBalance,
      String accrued,
      String used,
      String adjustment,
      String pending,
      String available,
      @Nullable String utilizationPct,
      String legacyAvailable) {}

  public record LeaveUtilizationByType(
      int leaveTypeId,
      String leaveTypeName,
      int employees,
      String totalAccrued,
      String totalUsed,
      @Nullable String avgUtilizationPct) {}

  public record LeaveSummary(List<LeaveUtilizationByType> byLeaveType) {}

  public record LeaveSummaryPage(
      LocalDate asOf,
      int year,
      List<LeaveSummaryRow> content,
      PageMeta page,
      LeaveSummary summary) {}

  // ---- payroll latest
  public record PayrollLatestRow(
      long empId,
      String empNumber,
      String empName,
      String deptName,
      long periodId,
      String periodName,
      LocalDate payDate,
      long runId,
      String runType,
      String runStatus,
      String grossPay,
      String totalTaxes,
      String totalDeductions,
      String netPay) {}

  public record PayrollSummary(
      @Nullable Long periodId,
      int employeeCount,
      String totalGross,
      String totalTaxes,
      String totalDeductions,
      String totalNet,
      String avgNet) {}

  public record PayrollLatestPage(
      List<PayrollLatestRow> content, PageMeta page, PayrollSummary summary) {}

  // ---- pending approvals
  public record PendingApprovalRow(
      String itemType,
      long itemId,
      long empId,
      String empNumber,
      String empName,
      String deptName,
      @Nullable Long approverEmpId,
      @Nullable String approverName,
      LocalDate submittedDate,
      int daysPending,
      String detail) {}

  public record PendingSummary(int leave, int review) {}

  public record PendingApprovalPage(
      LocalDate asOf, List<PendingApprovalRow> content, PageMeta page, PendingSummary summary) {}
}
