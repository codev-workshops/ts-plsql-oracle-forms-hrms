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
