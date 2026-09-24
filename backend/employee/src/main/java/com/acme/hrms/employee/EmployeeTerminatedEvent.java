package com.acme.hrms.employee;

import java.time.LocalDate;

/** Published after a successful termination (consumed by leave / payroll in later phases). */
public record EmployeeTerminatedEvent(
    long empId, LocalDate terminationDate, String reason, String actor) {}
