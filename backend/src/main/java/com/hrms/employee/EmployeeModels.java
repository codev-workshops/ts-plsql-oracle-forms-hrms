package com.hrms.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Employee command API models (contract amendment v1.1-P5). EMP_ID, EMP_NUMBER, status and the audit columns are
 * never accepted on write (FRM-038); unknown JSON properties are ignored.
 */
public final class EmployeeModels {

    private EmployeeModels() {
    }

    public enum EmploymentType { FULL_TIME, PART_TIME, CONTRACT, INTERN }

    public enum WarningCode { SALARY_OUTSIDE_GRADE }

    /** A non-blocking finding returned with a successful write (OQ-11). */
    public record Warning(WarningCode code, String message) {
    }

    public record EmployeeMutation(EmployeeView employee, List<Warning> warnings) {
    }

    public record CreateEmployeeRequest(
            @Size(max = 50) String firstName,
            @Size(max = 50) String lastName,
            @NotNull LocalDate hireDate,
            @NotNull Long deptId,
            @NotNull Long jobId,
            Long managerEmpId,
            @Size(max = 10) String locationCode,
            EmploymentType employmentType,
            BigDecimal baseSalary,
            @Size(max = 100) String email,
            @Size(max = 30) String phoneWork,
            @Size(max = 30) String phoneMobile,
            LocalDate dateOfBirth) {
    }

    public record UpdateEmployeeRequest(
            @Size(max = 50) String firstName,
            @Size(max = 50) String lastName,
            @Size(max = 100) String email,
            @Size(max = 30) String phoneWork,
            @Size(max = 30) String phoneMobile,
            @Size(max = 200) String addressLine1,
            @Size(max = 200) String addressLine2,
            @Size(max = 100) String city,
            @Size(max = 100) String stateProvince,
            @Size(max = 20) String postalCode,
            @Size(max = 3) String countryCode) {
    }

    public record TransferRequest(
            @NotNull Long newDeptId,
            Long newJobId,
            Long newManagerEmpId,
            @Size(max = 10) String newLocationCode,
            LocalDate effectiveDate,
            @Size(max = 30) String reasonCode,
            @Size(max = 4000) String comments) {
    }

    public record PromoteRequest(
            @NotNull Long newJobId,
            @NotNull BigDecimal newSalary,
            LocalDate effectiveDate,
            @Size(max = 4000) String comments) {
    }

    public record TerminateRequest(
            @NotNull LocalDate terminationDate,
            @NotBlank @Size(max = 30) String reason,
            @Size(max = 4000) String comments) {
    }

    public record RehireRequest(
            @NotNull LocalDate rehireDate,
            @NotNull Long deptId,
            @NotNull Long jobId,
            @NotNull BigDecimal baseSalary,
            Long managerEmpId,
            @Size(max = 10) String locationCode) {
    }

    public record EmployeeHistoryEntry(long histId, String changeType, LocalDate effectiveDate, Long oldDeptId,
            Long newDeptId, Long oldJobId, Long newJobId, Long oldManagerId, Long newManagerId,
            BigDecimal oldSalary, BigDecimal newSalary, String oldLocation, String newLocation, String reasonCode,
            String comments, String createdBy, OffsetDateTime createdDate) {
    }
}
