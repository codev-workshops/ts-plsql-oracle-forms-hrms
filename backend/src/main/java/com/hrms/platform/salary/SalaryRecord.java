package com.hrms.platform.salary;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalaryRecord(
        long salaryId,
        long empId,
        LocalDate effectiveDate,
        LocalDate endDate,
        BigDecimal baseSalary,
        String currencyCode,
        String payFrequency,
        String salaryBasis,
        String changeReason,
        BigDecimal changePct,
        boolean active) {
}
