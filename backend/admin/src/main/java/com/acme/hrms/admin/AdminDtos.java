package com.acme.hrms.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** Response shapes of the frozen P5 admin contract (property order = declaration order). */
public final class AdminDtos {
  private AdminDtos() {}

  public record Department(
      long deptId,
      String deptCode,
      String deptName,
      @Nullable Long parentDeptId,
      @Nullable String parentDeptName,
      @Nullable String costCenter,
      @Nullable Long managerEmpId,
      @Nullable String managerName,
      @Nullable String locationCode,
      boolean activeFlag,
      int activeEmployees,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record JobGrade(
      int gradeId,
      String gradeCode,
      String gradeName,
      String minSalary,
      String maxSalary,
      boolean overtimeEligible,
      boolean activeFlag,
      int activeJobTitles,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record JobTitle(
      long jobId,
      String jobCode,
      String jobTitle,
      @Nullable String jobFamily,
      int gradeId,
      String gradeCode,
      @Nullable String eeoCategory,
      String flsaStatus,
      boolean activeFlag,
      int activeEmployees,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record Location(
      String locationCode,
      String locationName,
      @Nullable String addressLine1,
      @Nullable String addressLine2,
      @Nullable String city,
      @Nullable String stateProvince,
      @Nullable String postalCode,
      @Nullable String countryCode,
      @Nullable String phoneNumber,
      String timezone,
      boolean activeFlag,
      int activeEmployees,
      int activeDepartments,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record LeaveType(
      int leaveTypeId,
      String leaveTypeCode,
      String leaveTypeName,
      boolean paidFlag,
      boolean accrualFlag,
      @Nullable String accrualRate,
      @Nullable String accrualFrequency,
      @Nullable String maxBalance,
      @Nullable String carryoverMax,
      @Nullable Integer carryoverExpiry,
      int minTenureDays,
      boolean requiresApproval,
      boolean requiresDocument,
      boolean activeFlag,
      int pendingRequests,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record SystemParameter(
      int paramId,
      String paramGroup,
      String paramCode,
      String paramValue,
      @Nullable String paramDescription,
      String dataType,
      boolean editableFlag,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record BatchRunResult(
      UUID jobId,
      String jobType,
      String status,
      int processed,
      int skipped,
      int failed,
      LocalDateTime startedAt,
      @Nullable LocalDateTime finishedAt,
      String startedBy,
      @Nullable String message) {}

  public record AuditLogRow(
      long auditId,
      String tableName,
      long recordId,
      String actionType,
      @Nullable String oldValues,
      @Nullable String newValues,
      String changedBy,
      LocalDateTime changedDate,
      @Nullable String ipAddress,
      @Nullable String sessionId) {}

  public record PageMeta(int page, int size, long totalElements, int totalPages) {
    public static PageMeta of(int page, int size, long total) {
      return new PageMeta(page, size, total, (int) ((total + size - 1) / size));
    }
  }

  public record AuditLogPage(List<AuditLogRow> content, PageMeta page) {}

  static String money(@Nullable BigDecimal v) {
    return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }
}
