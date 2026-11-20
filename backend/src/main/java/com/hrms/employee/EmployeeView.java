package com.hrms.employee;

import java.time.LocalDate;

/**
 * Employee read model (P2): identity, status, manager, location and hire date, plus the joined names
 * the Forms POST-QUERY looked up (FRM-043). No SSN, birth date, address or salary.
 */
public record EmployeeView(long empId, String empNumber, String firstName, String lastName, String email,
        String employmentStatus, String employmentType, boolean active, LocalDate hireDate,
        Long managerEmpId, String managerName, String locationCode, String locationName,
        long deptId, String deptName, long jobId, String jobTitle) {
}
