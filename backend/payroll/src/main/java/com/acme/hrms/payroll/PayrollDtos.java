package com.acme.hrms.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.lang.Nullable;

/** Response shapes of contracts/p4-payroll/openapi.yaml; money is a two-decimal string. */
public final class PayrollDtos {

  private PayrollDtos() {}

  public static String money(@Nullable BigDecimal value) {
    return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  @Nullable
  public static String nullableMoney(@Nullable BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  @Nullable
  public static String rate(@Nullable BigDecimal value) {
    return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> Page<T> of(List<T> content, int page, int size, long total) {
      return new Page<>(content, page, size, total, (int) Math.ceil(total / (double) size));
    }
  }

  public record PayPeriod(
      long periodId,
      String periodName,
      String payFrequency,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      LocalDate payDate,
      String status,
      @Nullable String closedBy,
      @Nullable LocalDateTime closedDate,
      int runCount,
      @Nullable Long latestRunId,
      @Nullable String latestRunStatus) {}

  public record PayrollRun(
      long runId,
      long periodId,
      String runType,
      LocalDateTime runDate,
      String status,
      String totalGross,
      String totalDeductions,
      String totalNet,
      @Nullable String totalEmployerCost,
      int employeeCount,
      int errorCount,
      String engine,
      @Nullable String submittedBy,
      @Nullable LocalDateTime submittedDate,
      @Nullable String approvedBy,
      @Nullable LocalDateTime approvedDate,
      String createdBy,
      LocalDateTime createdDate) {}

  public record PayrollRunStatus(
      long runId,
      String status,
      @Nullable Long jobExecutionId,
      int processed,
      @Nullable Integer total,
      int errorCount,
      @Nullable LocalDateTime startedAt,
      @Nullable LocalDateTime finishedAt,
      @Nullable String failureMessage) {}

  public record ApprovalWarning(
      long empId, String empNumber, String errorCode, String errorMessage) {}

  public record PayrollRunApproval(PayrollRun run, List<ApprovalWarning> warnings) {}

  public record PayrollDetail(
      long detailId,
      long runId,
      long empId,
      String empNumber,
      long elementId,
      String elementCode,
      String elementType,
      @Nullable String hoursWorked,
      @Nullable String rate,
      String amount,
      @Nullable String ytdAmount,
      String status,
      @Nullable String errorCode,
      @Nullable String errorMessage) {}

  public record Payslip(
      long runId,
      long empId,
      String empNumber,
      String empName,
      @Nullable String departmentName,
      @Nullable String jobTitle,
      String periodName,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      LocalDate payDate,
      String runStatus,
      String grossPay,
      String federalTax,
      String stateTax,
      String socialSecurity,
      String medicare,
      String otherDeductions,
      String totalDeductions,
      String netPay,
      String ytdGross,
      String ytdTaxes,
      String ytdDeductions,
      String ytdNet,
      List<PayrollDetail> lines) {}

  public record ShadowSummary(
      int employees,
      int matched,
      int explained,
      int unexplained,
      int legacyOnly,
      int javaOnly,
      int errorRowsLegacy,
      int errorRowsJava,
      long netDeltaCents) {}

  public record ShadowLine(
      long empId,
      @Nullable String empNumber,
      long elementId,
      @Nullable String elementCode,
      String classification,
      @Nullable String explanation,
      @Nullable String legacyAmount,
      @Nullable String javaAmount,
      long deltaCents) {}

  public record ShadowDiffReport(
      long runId,
      long periodId,
      int taxYear,
      String engineFlag,
      String legacySource,
      LocalDateTime comparedAt,
      ShadowSummary summary,
      List<ShadowLine> lines) {}
}
