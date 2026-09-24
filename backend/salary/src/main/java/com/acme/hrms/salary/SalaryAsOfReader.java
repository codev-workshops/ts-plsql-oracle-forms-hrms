package com.acme.hrms.salary;

import com.acme.hrms.salary.SalaryDtos.SalaryRecord;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Read-only salary-module boundary for other modules (P4 payroll): the salary in force on a date
 * (PKG_PAYROLL.get_salary_as_of). Callers never see {@link SalaryRecordRepository}; only
 * salary-module reads or writes {@code salary_records}.
 */
public interface SalaryAsOfReader {

  /**
   * Salary effective on {@code asOf}: the latest {@code effective_date} not after the date whose
   * {@code end_date} is null or not before it. Empty when the employee has no such row.
   */
  Optional<SalaryRecord> effectiveOn(long empId, LocalDate asOf);
}
