package com.hrms.payroll;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Payroll API models (contract amendment v1.2-P6). */
public final class PayrollModels {

    private PayrollModels() {
    }

    public enum PayFrequency {
        WEEKLY(52), BIWEEKLY(26), SEMIMONTHLY(24), MONTHLY(12);

        private final int periodsPerYear;

        PayFrequency(int periodsPerYear) {
            this.periodsPerYear = periodsPerYear;
        }

        public int periodsPerYear() {
            return periodsPerYear;
        }
    }

    public enum PayPeriodStatus { OPEN, PROCESSING, CLOSED, REVERSED }

    public enum PayrollRunStatus { PENDING, CALCULATING, CALCULATED, APPROVED, PAID, REVERSED, ERROR }

    public enum PayrollRunType { REGULAR, SUPPLEMENTAL, BONUS, FINAL }

    public record PayPeriod(long periodId, String periodName, PayFrequency payFrequency, LocalDate periodStartDate,
            LocalDate periodEndDate, LocalDate payDate, PayPeriodStatus status, String closedBy,
            OffsetDateTime closedDate) {
    }

    public record GeneratePayPeriodsRequest(@NotNull @Min(1900) @Max(9999) Integer year,
            @NotNull PayFrequency frequency) {
    }

    public record PayPeriodGeneration(int year, PayFrequency frequency, List<PayPeriod> created, int skipped) {
    }

    public record CreatePayrollRunRequest(@NotNull Long periodId, PayrollRunType runType) {
    }

    public record ReversePayrollRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record PayrollRun(long runId, long periodId, String periodName, PayrollRunType runType,
            OffsetDateTime runDate, PayrollRunStatus status, BigDecimal totalGross, BigDecimal totalDeductions,
            BigDecimal totalNet, Integer employeeCount, int errorCount, String submittedBy,
            OffsetDateTime submittedDate, String approvedBy, OffsetDateTime approvedDate) {
    }

    public record PayrollRunError(long detailId, long empId, String message) {
    }

    public record Payslip(long empId, String empNumber, String employeeName, String periodName, LocalDate payDate,
            BigDecimal grossPay, BigDecimal totalDeductions, BigDecimal netPay, BigDecimal federalTax,
            BigDecimal stateTax, BigDecimal socialSecurity, BigDecimal medicare, BigDecimal ytdGross,
            BigDecimal ytdNet) {
    }

    public record SalaryRecord(long salaryId, long empId, LocalDate effectiveDate, LocalDate endDate,
            BigDecimal baseSalary, String currencyCode, PayFrequency payFrequency, String salaryBasis,
            String changeReason, BigDecimal changePct, boolean active) {
    }
}
