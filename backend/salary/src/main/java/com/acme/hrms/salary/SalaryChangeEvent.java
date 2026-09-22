package com.acme.hrms.salary;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalaryChangeEvent(
    long empId,
    LocalDate effectiveDate,
    BigDecimal oldSalary,
    BigDecimal newSalary,
    String reason,
    String actor) {}
