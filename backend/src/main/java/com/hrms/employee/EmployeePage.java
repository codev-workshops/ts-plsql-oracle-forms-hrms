package com.hrms.employee;

import java.util.List;

public record EmployeePage(List<EmployeeView> items, int page, int size, long total) {
}
