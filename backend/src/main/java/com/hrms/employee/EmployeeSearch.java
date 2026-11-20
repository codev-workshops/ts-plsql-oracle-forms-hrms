package com.hrms.employee;

import java.time.LocalDate;

/** Parameterized replacement for PKG_EMPLOYEE.search_employees (DEF-09); every value is a bind. */
public record EmployeeSearch(String lastName, String firstName, Long deptId, Long managerEmpId, String status,
        String locationCode, LocalDate hireDateFrom, LocalDate hireDateTo, boolean includeInactive,
        int page, int size) {
}
