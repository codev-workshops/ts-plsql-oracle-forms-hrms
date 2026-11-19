package com.hrms.payroll;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class PayrollModels {
    private PayrollModels() {}

    public enum Frequency {
        WEEKLY(52), BIWEEKLY(26), SEMIMONTHLY(24), MONTHLY(12);

        final int periodsPerYear;

        Frequency(int periodsPerYear) {
            this.periodsPerYear = periodsPerYear;
        }
    }

    public enum PeriodFrequency { MONTHLY, BIWEEKLY }
    public enum PeriodFilter { OPEN, CLOSED, PROCESSING }
    public enum RunType { REGULAR, OFF_CYCLE, BONUS, CORRECTION }
    public enum FilingStatus { SINGLE, MARRIED_JOINT, MARRIED_SEPARATE, HEAD_OF_HOUSEHOLD }
    public enum ElementType { EARNING, DEDUCTION, TAX, BENEFIT }

    public record CreatePeriods(@NotNull @Min(1) @Max(9999) Integer year,
                                @NotNull PeriodFrequency frequency) {}
    public record CreateRun(@NotNull @Positive Long periodId, RunType runType) {}
    public record ChangeSalary(@NotNull BigDecimal baseSalary, @NotNull LocalDate effectiveDate,
                               @NotNull @Size(max = 50) String changeReason, Frequency payFrequency) {}

    public record PayPeriod(long periodId, String periodName, String frequency, LocalDate startDate,
                            LocalDate endDate, LocalDate payDate, String status) {}
    public record PayrollRun(long runId, long periodId, String runType, String status,
                             int employeeCount, BigDecimal totalGross, BigDecimal totalDeductions,
                             BigDecimal totalNet, LocalDateTime calculatedAt, String approvedBy) {}
    public record PayrollDetail(long detailId, long empId, Long elementId, String elementCode,
                                ElementType elementType, BigDecimal amount, String status,
                                String errorMessage) {}
    public record Payslip(long runId, long empId, String employeeName, PayPeriod period,
                          List<PayrollDetail> lines, BigDecimal gross, BigDecimal deductions,
                          BigDecimal taxes, BigDecimal net, BigDecimal ytdGross, BigDecimal ytdTaxes,
                          BigDecimal ytdNet) {}
    public record Ytd(int year, BigDecimal gross, BigDecimal deductions, BigDecimal taxes, BigDecimal net) {}

    public record EmployeeElement(long empElementId, long empId, long elementId, String elementCode,
                                  String elementName, String elementType, String calculationType,
                                  BigDecimal amount, BigDecimal percentage, BigDecimal overrideAmount,
                                  BigDecimal defaultAmount, BigDecimal defaultPercentage, boolean pretax,
                                  int priorityOrder, LocalDate effectiveDate, LocalDate endDate) {}
    public record TaxInfo(long taxInfoId, long empId, int taxYear, FilingStatus filingStatus,
                          int federalAllowances, int stateAllowances, BigDecimal additionalFedWh,
                          BigDecimal additionalStateWh, boolean exempt, String stateCode, LocalDate w4ReceivedDate) {}
    public record TaxBracket(long bracketId, int taxYear, FilingStatus filingStatus, BigDecimal bracketMin,
                             BigDecimal bracketMax, BigDecimal taxRate, BigDecimal baseTax, String stateCode) {}
}
