package com.hrms.employee;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The employee module's outbound port for compensation (OQ-01). Replaces the direct calls from PKG_EMPLOYEE to
 * PKG_PAYROLL.create_salary_record (create, promote, rehire) and terminate's direct updates of SALARY_RECORDS and
 * EMPLOYEE_PAY_ELEMENTS. Called in-process and synchronously inside the employee transaction, so a failure rolls
 * the whole hire / promotion / rehire / termination back. Implemented by payroll
 * ({@code com.hrms.payroll.PayrollSalaryRecords}, PKG-P01); the employee module never depends on payroll.
 */
public interface SalaryRecordPort {

    enum ChangeReason { NEW_HIRE, PROMOTION, REHIRE }

    /** A new annual base salary effective on {@code effectiveDate} (PKG_PAYROLL.create_salary_record). */
    record SalaryChange(long empId, LocalDate effectiveDate, BigDecimal baseSalary, ChangeReason reason,
            BigDecimal changePct, String actor) {
    }

    void createSalaryRecord(SalaryChange change);

    /** The active base salary on {@code asOf}, if any (read by promote to compute the change percentage). */
    Optional<BigDecimal> currentBaseSalary(long empId, LocalDate asOf);

    /** End-dates the active salary record and pay elements at {@code endDate} (terminate). */
    void endCompensation(long empId, LocalDate endDate, String actor);
}
