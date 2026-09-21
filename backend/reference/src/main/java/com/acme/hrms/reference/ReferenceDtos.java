package com.acme.hrms.reference;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

/** Wire shapes of the reference endpoints, exactly as in contracts/p0-foundation/openapi.yaml. */
public final class ReferenceDtos {

  private ReferenceDtos() {}

  public record DepartmentRef(
      long deptId,
      String deptCode,
      String deptName,
      @Nullable Long parentDeptId,
      @Nullable String locationCode,
      boolean active) {}

  public record JobTitleRef(
      long jobId,
      String jobCode,
      String jobTitle,
      @Nullable String jobFamily,
      int gradeId,
      String gradeCode,
      String gradeName,
      String gradeMinSalary,
      String gradeMaxSalary,
      boolean active) {}

  public record LocationRef(
      String locationCode,
      String locationName,
      @Nullable String city,
      @Nullable String stateProvince,
      @Nullable String countryCode,
      String timezone,
      boolean active) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record LeaveTypeRef(
      int leaveTypeId,
      String leaveTypeCode,
      String leaveTypeName,
      boolean paid,
      boolean accrual,
      @Nullable String accrualRate,
      @Nullable String maxBalance,
      @Nullable String carryoverMax,
      int minTenureDays,
      boolean requiresApproval,
      boolean requiresDocument,
      boolean active) {}

  public record EmployeeSummary(
      long id, String empNumber, String name, @Nullable String jobTitle) {}

  public record PageOfEmployeeSummary(
      java.util.List<EmployeeSummary> content,
      int page,
      int size,
      long totalElements,
      int totalPages) {}
}
