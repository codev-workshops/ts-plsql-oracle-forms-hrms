package com.acme.hrms.employee;

import com.acme.hrms.employee.EmployeeDtos.EmployeeDetail;
import com.acme.hrms.employee.EmployeeDtos.EmployeeListItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.lang.Nullable;

/**
 * One {@code employees} row joined to its department / job / grade / manager / location names
 * ({@code VW_EMPLOYEE_DETAILS} semantics). Internal to employee-service: carries the encrypted SSN,
 * which never leaves the module.
 */
public record EmployeeRow(
    long empId,
    String empNumber,
    String firstName,
    @Nullable String middleName,
    String lastName,
    @Nullable LocalDate dateOfBirth,
    @Nullable String gender,
    @Nullable String maritalStatus,
    @Nullable String nationality,
    @Nullable String ssnEncrypted,
    @Nullable String email,
    @Nullable String phoneWork,
    @Nullable String phoneMobile,
    @Nullable String addressLine1,
    @Nullable String addressLine2,
    @Nullable String city,
    @Nullable String stateProvince,
    @Nullable String postalCode,
    @Nullable String countryCode,
    LocalDate hireDate,
    @Nullable LocalDate terminationDate,
    @Nullable String terminationReason,
    long deptId,
    String deptName,
    long jobId,
    String jobTitle,
    @Nullable String gradeCode,
    @Nullable Long managerEmpId,
    @Nullable String managerName,
    @Nullable String locationCode,
    @Nullable String locationName,
    String employmentType,
    String employmentStatus,
    boolean active,
    @Nullable String notes,
    int version,
    String createdBy,
    LocalDateTime createdDate,
    @Nullable String modifiedBy,
    @Nullable LocalDateTime modifiedDate) {

  public boolean terminated() {
    return "TERMINATED".equals(employmentStatus);
  }

  public EmployeeDetail toDetail(@Nullable String ssnLast4) {
    return new EmployeeDetail(
        empId,
        empNumber,
        firstName,
        middleName,
        lastName,
        dateOfBirth,
        gender,
        maritalStatus,
        nationality,
        ssnLast4,
        email,
        phoneWork,
        phoneMobile,
        addressLine1,
        addressLine2,
        city,
        stateProvince,
        postalCode,
        countryCode,
        hireDate,
        terminationDate,
        terminationReason,
        deptId,
        deptName,
        jobId,
        jobTitle,
        gradeCode,
        managerEmpId,
        managerName,
        locationCode,
        locationName,
        employmentType,
        employmentStatus,
        active,
        notes,
        version,
        createdBy,
        createdDate,
        modifiedBy,
        modifiedDate);
  }

  public EmployeeListItem toListItem() {
    return new EmployeeListItem(
        empId,
        empNumber,
        firstName,
        lastName,
        email,
        deptId,
        deptName,
        jobId,
        jobTitle,
        managerEmpId,
        managerName,
        locationCode,
        hireDate,
        employmentType,
        employmentStatus,
        active);
  }
}
