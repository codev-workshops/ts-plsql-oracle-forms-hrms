package com.acme.hrms.employee;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import org.springframework.lang.Nullable;

/** Wire representations of the employee schemas in contracts/p3-employee/openapi.yaml. */
public final class EmployeeDtos {

  private EmployeeDtos() {}

  /** {@code EmployeeDetail}: never carries the SSN, encrypted SSN, photo or salary. */
  public record EmployeeDetail(
      long id,
      String empNumber,
      String firstName,
      @Nullable String middleName,
      String lastName,
      @Nullable LocalDate dateOfBirth,
      @Nullable String gender,
      @Nullable String maritalStatus,
      @Nullable String nationality,
      @Nullable String ssnLast4,
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

    public EmployeeDetail withSsnLast4(@Nullable String last4) {
      return new EmployeeDetail(
          id,
          empNumber,
          firstName,
          middleName,
          lastName,
          dateOfBirth,
          gender,
          maritalStatus,
          nationality,
          last4,
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
  }

  public record EmployeeListItem(
      long id,
      String empNumber,
      String firstName,
      String lastName,
      @Nullable String email,
      long deptId,
      String deptName,
      long jobId,
      String jobTitle,
      @Nullable Long managerEmpId,
      @Nullable String managerName,
      @Nullable String locationCode,
      LocalDate hireDate,
      String employmentType,
      String employmentStatus,
      boolean active) {}

  public record EmployeeSummary(
      long id, String empNumber, String name, @Nullable String jobTitle) {}

  public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> Page<T> of(List<T> content, int page, int size, long total) {
      return new Page<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    public <R> Page<R> mapContent(Function<T, R> f) {
      return new Page<>(content.stream().map(f).toList(), page, size, totalElements, totalPages);
    }
  }

  public record EmployeeHistoryEntry(
      long histId,
      long empId,
      String changeType,
      LocalDate effectiveDate,
      @Nullable Long oldDeptId,
      @Nullable String oldDeptName,
      @Nullable Long newDeptId,
      @Nullable String newDeptName,
      @Nullable Long oldJobId,
      @Nullable String oldJobTitle,
      @Nullable Long newJobId,
      @Nullable String newJobTitle,
      @Nullable Long oldManagerId,
      @Nullable String oldManagerName,
      @Nullable Long newManagerId,
      @Nullable String newManagerName,
      @Nullable String oldSalary,
      @Nullable String newSalary,
      @Nullable String oldLocation,
      @Nullable String newLocation,
      @Nullable String reasonCode,
      @Nullable String comments,
      String createdBy,
      LocalDateTime createdDate) {

    public EmployeeHistoryEntry withoutSalary() {
      return new EmployeeHistoryEntry(
          histId,
          empId,
          changeType,
          effectiveDate,
          oldDeptId,
          oldDeptName,
          newDeptId,
          newDeptName,
          oldJobId,
          oldJobTitle,
          newJobId,
          newJobTitle,
          oldManagerId,
          oldManagerName,
          newManagerId,
          newManagerName,
          null,
          null,
          oldLocation,
          newLocation,
          reasonCode,
          comments,
          createdBy,
          createdDate);
    }
  }

  public record Dependent(
      long dependentId,
      long empId,
      String firstName,
      String lastName,
      String relationship,
      @Nullable LocalDate dateOfBirth,
      @Nullable String ssnLast4,
      boolean benefitsEnrolled,
      boolean active) {}

  public record EmergencyContact(
      long contactId,
      long empId,
      String contactName,
      @Nullable String relationship,
      String phonePrimary,
      @Nullable String phoneSecondary,
      @Nullable String email,
      int priorityOrder,
      boolean active) {}
}
