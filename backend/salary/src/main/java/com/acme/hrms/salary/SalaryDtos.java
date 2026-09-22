package com.acme.hrms.salary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.lang.Nullable;

/** Wire representations of the salary schemas in contracts/p3-employee/openapi.yaml. */
public final class SalaryDtos {

  private SalaryDtos() {}

  public record SalaryRecord(
      long salaryId,
      long empId,
      LocalDate effectiveDate,
      @Nullable LocalDate endDate,
      String baseSalary,
      String currencyCode,
      String payFrequency,
      String salaryBasis,
      @Nullable String changeReason,
      @Nullable String changePct,
      boolean active,
      boolean outOfGradeBand,
      String createdBy,
      LocalDateTime createdDate) {}
}
