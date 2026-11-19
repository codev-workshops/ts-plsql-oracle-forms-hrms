package com.hrms.platform.employee;

import java.time.LocalDate;

/** Read-only employee projection shared by every module (inventory §8 EmployeeLookup). */
public record EmployeeSummary(
        long empId,
        String empNumber,
        String firstName,
        String lastName,
        String fullName,
        String email,
        long deptId,
        String deptName,
        long jobId,
        String jobTitle,
        int gradeLevel,
        Long managerEmpId,
        String locationCode,
        LocalDate hireDate,
        String employmentStatus,
        boolean active) {

    public boolean isActiveEmployee() {
        return active && "ACTIVE".equals(employmentStatus);
    }
}
