package com.hrms.employee;

import com.hrms.common.ApiException;
import com.hrms.common.ErrorCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Employee read endpoints. EMPLOYEE:VIEW is granted to everyone by the legacy matrix (D-06 rule 4). */
@RestController
@RequestMapping("/api/v1/employees")
@PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
public class EmployeeController {

    private final EmployeeReadService employees;

    public EmployeeController(EmployeeReadService employees) {
        this.employees = employees;
    }

    @GetMapping
    EmployeePage search(
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long managerEmpId,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ON_LEAVE|SUSPENDED|TERMINATED") String status,
            @RequestParam(required = false) String locationCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hireDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hireDateTo,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return employees.search(new EmployeeSearch(blankToNull(lastName), blankToNull(firstName), deptId, managerEmpId,
                status, blankToNull(locationCode), hireDateFrom, hireDateTo, includeInactive, page, size));
    }

    @GetMapping("/{empId}")
    EmployeeView get(@PathVariable long empId, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return employees.findById(empId, includeInactive)
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
