package com.hrms.employee;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hrms.platform.salary.SalaryRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class EmployeeModels {

    private EmployeeModels() {
    }

    /** Full employees row joined with its department, job and location keys. */
    public record EmployeeRow(long empId, String empNumber, String firstName, String middleName, String lastName,
                              String email, long deptId, String deptName, long jobId, String jobTitle,
                              int gradeLevel, Long managerEmpId, String locationCode, Long locationId,
                              LocalDate hireDate, String employmentStatus, boolean active, LocalDate dateOfBirth,
                              String gender, String maritalStatus, String phoneWork, String phoneMobile,
                              String addressLine1, String addressLine2, String city, String state,
                              String postalCode, String country, String employmentType,
                              LocalDate terminationDate, String terminationReason) {

        public String fullName() {
            return firstName + " " + lastName;
        }

        public boolean isActiveEmployee() {
            return active && "ACTIVE".equals(employmentStatus);
        }
    }

    public record EmployeeDetail(long empId, String empNumber, String firstName, String middleName, String lastName,
                                 String fullName, String email, long deptId, String deptName, long jobId,
                                 String jobTitle, int gradeLevel, Long managerEmpId, String locationCode,
                                 Long locationId, LocalDate hireDate, String employmentStatus, boolean active,
                                 LocalDate dateOfBirth, String gender, String maritalStatus, String phoneWork,
                                 String phoneMobile, String addressLine1, String addressLine2, String city,
                                 String state, String postalCode, String country, String employmentType,
                                 LocalDate terminationDate, String terminationReason, BigDecimal tenureYears,
                                 SalaryRecord currentSalary, List<String> warnings) {

        static EmployeeDetail of(EmployeeRow r, BigDecimal tenureYears, SalaryRecord salary, List<String> warnings) {
            return new EmployeeDetail(r.empId(), r.empNumber(), r.firstName(), r.middleName(), r.lastName(),
                    r.fullName(), r.email(), r.deptId(), r.deptName(), r.jobId(), r.jobTitle(), r.gradeLevel(),
                    r.managerEmpId(), r.locationCode(), r.locationId(), r.hireDate(), r.employmentStatus(),
                    r.active(), r.dateOfBirth(), r.gender(), r.maritalStatus(), r.phoneWork(), r.phoneMobile(),
                    r.addressLine1(), r.addressLine2(), r.city(), r.state(), r.postalCode(), r.country(),
                    r.employmentType(), r.terminationDate(), r.terminationReason(), tenureYears, salary,
                    warnings);
        }
    }

    public record EmployeeCreate(String firstName, String middleName, String lastName,
                                 @NotBlank @Size(max = 100) String email, String phoneWork, String phoneMobile,
                                 LocalDate dateOfBirth, String gender, @Size(max = 10) String maritalStatus,
                                 @NotNull LocalDate hireDate, @NotNull Long deptId, @NotNull Long jobId,
                                 Long managerEmpId, Long locationId, String employmentType,
                                 @Positive BigDecimal baseSalary) {
    }

    public record EmployeeUpdate(String firstName, String middleName, String lastName, @Size(max = 100) String email,
                                 String phoneWork, String phoneMobile, @Size(max = 200) String addressLine1,
                                 @Size(max = 200) String addressLine2, @Size(max = 100) String city,
                                 @Size(max = 100) String state, @Size(max = 20) String postalCode,
                                 @Size(max = 3) String country) {
    }

    public record TransferRequest(@NotNull Long deptId, Long jobId, Long locationId, Long managerEmpId,
                                  @NotNull LocalDate effectiveDate, @Size(max = 4000) String reason) {
    }

    public record PromoteRequest(@NotNull Long jobId, @NotNull @Positive BigDecimal newSalary,
                                 @NotNull LocalDate effectiveDate, @Size(max = 4000) String reason) {
    }

    public record TerminateRequest(@NotNull LocalDate terminationDate, @NotBlank @Size(max = 50) String reason) {
    }

    public record RehireRequest(@NotNull LocalDate hireDate, @NotNull Long deptId, @NotNull Long jobId,
                                Long managerEmpId, @Positive BigDecimal baseSalary) {
    }

    public record OrgChartNode(long empId, String fullName, String jobTitle, Long managerEmpId, int level) {
    }

    public record EmployeeHistory(long historyId, String changeType, LocalDate effectiveDate,
                                  Map<String, Object> oldValues, Map<String, Object> newValues, String reason,
                                  String createdBy, LocalDateTime createdDate) {
    }

    /** One row to append to employee_history; null fields are left empty. */
    public record HistoryEntry(long empId, String changeType, LocalDate effectiveDate, Long oldDeptId,
                               Long newDeptId, Long oldJobId, Long newJobId, Long oldManagerId, Long newManagerId,
                               BigDecimal oldSalary, BigDecimal newSalary, String oldLocation, String newLocation,
                               String reasonCode, String comments) {
    }

    public record Dependent(Long dependentId, @NotBlank @Size(max = 50) String firstName,
                            @NotBlank @Size(max = 50) String lastName, @NotBlank String relationship,
                            LocalDate dateOfBirth, String gender, Boolean benefitsEligible) {
    }

    public record EmergencyContact(Long contactId, @NotBlank @Size(max = 100) String contactName,
                                   @Size(max = 30) String relationship, @NotBlank String phonePrimary,
                                   String phoneSecondary, @Size(max = 100) String email,
                                   @JsonProperty("isPrimary") Boolean isPrimary) {
    }

    public record RefItem(long id, String code, String name, Integer gradeId) {
    }

    public record HeadcountRow(long deptId, String deptName, int headcount) {
    }

    record JobRef(long jobId, int gradeId, String gradeCode, BigDecimal minSalary, BigDecimal maxSalary,
                  boolean active) {
    }
}
