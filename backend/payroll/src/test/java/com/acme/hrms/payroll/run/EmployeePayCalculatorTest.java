package com.acme.hrms.payroll.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.hrms.payroll.period.PayPeriodRepository.PeriodCore;
import com.acme.hrms.payroll.run.PayrollDetailRepository.NewDetail;
import com.acme.hrms.payroll.tax.TaxEngine;
import com.acme.hrms.payroll.tax.TaxRules;
import com.acme.hrms.salary.SalaryAsOfReader;
import com.acme.hrms.salary.SalaryDtos.SalaryRecord;
import com.acme.hrms.validation.dto.payroll.PayrollConstants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * calculate_employee_pay failure contract (contracts/p4-payroll/error-codes.md §1.1) and the as-of
 * salary read through the salary-module boundary.
 */
class EmployeePayCalculatorTest {

  private static final PeriodCore PERIOD =
      new PeriodCore(
          202406,
          "JUN-2024",
          "MONTHLY",
          LocalDate.of(2024, 6, 1),
          LocalDate.of(2024, 6, 30),
          LocalDate.of(2024, 6, 30),
          "PROCESSING");
  private static final TaxRules RULES = TaxRules.of(2024, List.of(), Map.of());

  private final SalaryAsOfReader salaries = mock(SalaryAsOfReader.class);
  private final EmployeePayInputRepository inputs = mock(EmployeePayInputRepository.class);
  private final PayrollDetailRepository details = mock(PayrollDetailRepository.class);
  private final TaxEngine taxEngine = mock(TaxEngine.class);
  private final EmployeePayCalculator calculator =
      new EmployeePayCalculator(salaries, inputs, details, taxEngine);

  @Test
  void readsSalaryInForceOnPeriodEndDateThroughTheBoundary() {
    when(salaries.effectiveOn(43, PERIOD.periodEndDate())).thenReturn(Optional.empty());

    calculator.calculate(7, PERIOD, RULES, 43, "tester");

    verify(salaries).effectiveOn(43, LocalDate.of(2024, 6, 30));
    verifyNoInteractions(taxEngine);
  }

  @Test
  void noSalaryInForceIsOneSentinelErrorRow() {
    when(salaries.effectiveOn(43, PERIOD.periodEndDate())).thenReturn(Optional.empty());

    List<NewDetail> rows = calculator.calculate(7, PERIOD, RULES, 43, "tester");

    assertThat(rows).hasSize(1);
    assertErrorRow(rows.get(0), "-20104", "No active salary record for employee 43");
  }

  @Test
  void zeroSalaryIsTreatedAsNoActiveSalary() {
    when(salaries.effectiveOn(43, PERIOD.periodEndDate()))
        .thenReturn(Optional.of(salary(43, "0.00")));

    List<NewDetail> rows = calculator.calculate(7, PERIOD, RULES, 43, "tester");

    assertThat(rows).hasSize(1);
    assertErrorRow(rows.get(0), "-20104", "No active salary record for employee 43");
  }

  @Test
  void unexpectedFailureIsRecordedAsInternalErrorRowNotRethrown() {
    when(salaries.effectiveOn(2, PERIOD.periodEndDate()))
        .thenReturn(Optional.of(salary(2, "250000.00")));
    when(taxEngine.periodGross(new BigDecimal("250000.00"), "MONTHLY"))
        .thenReturn(new BigDecimal("20833.33"));
    when(details.taxYtdGross(anyLong(), anyInt()))
        .thenThrow(new IllegalStateException("connection lost"));

    List<NewDetail> rows = calculator.calculate(7, PERIOD, RULES, 2, "tester");

    assertThat(rows).hasSize(1);
    assertErrorRow(rows.get(0), "INTERNAL_ERROR", "An unexpected error occurred");
  }

  private static void assertErrorRow(NewDetail row, String code, String message) {
    assertThat(row.runId()).isEqualTo(7);
    assertThat(row.elementId()).isEqualTo(PayrollConstants.ERROR_ELEMENT_ID);
    assertThat(row.elementType()).isEqualTo("ERROR");
    assertThat(row.amount()).isEqualByComparingTo("0.00");
    assertThat(row.amount().scale()).isEqualTo(2);
    assertThat(row.ytdAmount()).isNull();
    assertThat(row.status()).isEqualTo("ERROR");
    assertThat(row.errorCode()).isEqualTo(code);
    assertThat(row.errorMessage()).isEqualTo(message);
    assertThat(row.createdBy()).isEqualTo("tester");
  }

  private static SalaryRecord salary(long empId, String baseSalary) {
    return new SalaryRecord(
        1,
        empId,
        LocalDate.of(2024, 1, 1),
        null,
        baseSalary,
        "USD",
        "MONTHLY",
        "ANNUAL",
        null,
        null,
        true,
        false,
        "seed",
        LocalDateTime.of(2024, 1, 1, 0, 0));
  }
}
