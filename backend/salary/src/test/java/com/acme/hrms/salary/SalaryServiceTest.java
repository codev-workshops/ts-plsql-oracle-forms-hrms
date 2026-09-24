package com.acme.hrms.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.acme.hrms.audit.AuditService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalaryServiceTest {

  private final SalaryService service =
      new SalaryService(
          mock(SalaryRecordRepository.class),
          mock(SalaryEmployeeLookup.class),
          mock(SalaryAccess.class),
          mock(AuditService.class),
          List.of());

  @Test
  void compaRatioRoundsToOneDecimalAndHandlesUnknownMidpoint() {
    assertThat(
            SalaryService.compaRatio(
                new BigDecimal("60000"), new BigDecimal("50000"), new BigDecimal("70000")))
        .isEqualByComparingTo("100.0");
    assertThat(
            SalaryService.compaRatio(
                new BigDecimal("60500"), new BigDecimal("50000"), new BigDecimal("70000")))
        .isEqualByComparingTo("100.8");
    assertThat(SalaryService.compaRatio(new BigDecimal("1"), BigDecimal.ZERO, BigDecimal.ZERO))
        .isNull();
    assertThat(SalaryService.compaRatio(new BigDecimal("1"), null, BigDecimal.TEN)).isNull();
  }

  @Test
  void changePctRoundsToTwoDecimalsAndHandlesMissingOrZeroPreviousSalary() {
    assertThat(SalaryService.changePct(new BigDecimal("450000"), new BigDecimal("460000")))
        .isEqualByComparingTo("2.22");
    assertThat(SalaryService.changePct(null, new BigDecimal("1"))).isNull();
    assertThat(SalaryService.changePct(BigDecimal.ZERO, new BigDecimal("1"))).isNull();
  }

  @Test
  void changePctIsTheExactlyRoundedFormulaForEveryMoneyMagnitude() {
    assertThat(SalaryService.changePct(new BigDecimal("66000.00"), new BigDecimal("999999.00")))
        .isEqualTo(new BigDecimal("1415.15"));
    assertThat(SalaryService.changePct(new BigDecimal("0.01"), new BigDecimal("9999999999.99")))
        .isEqualTo(new BigDecimal("99999999999800.00"));
    assertThat(SalaryService.changePct(new BigDecimal("9999999999.99"), new BigDecimal("0.01")))
        .isEqualTo(new BigDecimal("-100.00"));
    assertThat(SalaryService.changePct(new BigDecimal("66000.01"), new BigDecimal("66003.31")))
        .isEqualTo(new BigDecimal("0.00"));
    assertThat(
            SalaryService.changePct(
                new BigDecimal("9998000000.01"), new BigDecimal("9998499900.01")))
        .isEqualTo(new BigDecimal("0.00"));
    assertThat(
            SalaryService.changePct(new BigDecimal("12345678.91"), new BigDecimal("15562345.55")))
        .isEqualTo(new BigDecimal("26.05"));
    assertThat(
            SalaryService.changePct(new BigDecimal("9999999999.99"), new BigDecimal("500000.00")))
        .isEqualTo(new BigDecimal("-99.99"));
  }

  @Test
  void assertWithinGradeTreatsInclusiveBoundariesAsInBand() {
    assertThat(
            service.assertWithinGrade(
                new BigDecimal("50000"), new BigDecimal("50000"), new BigDecimal("70000")))
        .isFalse();
    assertThat(
            service.assertWithinGrade(
                new BigDecimal("70000"), new BigDecimal("50000"), new BigDecimal("70000")))
        .isFalse();
    assertThat(
            service.assertWithinGrade(
                new BigDecimal("70000.01"), new BigDecimal("50000"), new BigDecimal("70000")))
        .isTrue();
    assertThat(service.assertWithinGrade(new BigDecimal("1"), null, BigDecimal.TEN)).isFalse();
  }
}
