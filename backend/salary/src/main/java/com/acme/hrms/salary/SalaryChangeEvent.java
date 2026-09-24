package com.acme.hrms.salary;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.lang.Nullable;

/**
 * Emitted inside the {@link SalaryService} transaction after a SALARY_RECORDS row is inserted.
 * {@code kind} tells listeners whether the row is the opening salary of a new hire (already covered
 * by the caller's {@code HIRE} history) or an explicit salary change.
 */
public record SalaryChangeEvent(
    long empId,
    LocalDate effectiveDate,
    @Nullable BigDecimal oldSalary,
    BigDecimal newSalary,
    String reason,
    String actor,
    Kind kind) {

  public enum Kind {
    INITIAL,
    CHANGE
  }
}
