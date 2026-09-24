package com.acme.hrms.auth.web;

import com.acme.hrms.admin.AdminDtos.PageMeta;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Wire shapes of the {@code admin-roles} tag (contracts/p5-reporting-decommission/openapi.yaml).
 */
public final class RoleAdminDtos {
  private RoleAdminDtos() {}

  public record Role(
      int roleId,
      String roleCode,
      String roleName,
      int minGrade,
      int maxGrade,
      List<String> permissions,
      boolean seeded,
      int userCount,
      String createdBy,
      LocalDateTime createdDate) {}

  public record RoleWriteResult(
      int roleId,
      String roleCode,
      String roleName,
      int minGrade,
      int maxGrade,
      List<String> permissions,
      boolean seeded,
      int userCount,
      String createdBy,
      LocalDateTime createdDate,
      int sessionsRevoked) {
    public static RoleWriteResult of(Role r, int sessionsRevoked) {
      return new RoleWriteResult(
          r.roleId(),
          r.roleCode(),
          r.roleName(),
          r.minGrade(),
          r.maxGrade(),
          r.permissions(),
          r.seeded(),
          r.userCount(),
          r.createdBy(),
          r.createdDate(),
          sessionsRevoked);
    }
  }

  public record UserRoleGrant(
      int roleId, String roleCode, String roleName, String grantedBy, LocalDateTime grantedDate) {}

  public record UserAccount(
      long userId,
      long empId,
      String empNumber,
      String fullName,
      String username,
      String status,
      boolean locked,
      @Nullable LocalDateTime lockedUntil,
      int failedAttempts,
      boolean mustChangePassword,
      @Nullable LocalDateTime passwordChangedAt,
      List<UserRoleGrant> roles,
      List<String> authorities,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate) {}

  public record UserAccountWriteResult(
      long userId,
      long empId,
      String empNumber,
      String fullName,
      String username,
      String status,
      boolean locked,
      @Nullable LocalDateTime lockedUntil,
      int failedAttempts,
      boolean mustChangePassword,
      @Nullable LocalDateTime passwordChangedAt,
      List<UserRoleGrant> roles,
      List<String> authorities,
      String createdBy,
      LocalDateTime createdDate,
      @Nullable String modifiedBy,
      @Nullable LocalDateTime modifiedDate,
      int sessionsRevoked) {
    public static UserAccountWriteResult of(UserAccount u, int sessionsRevoked) {
      return new UserAccountWriteResult(
          u.userId(),
          u.empId(),
          u.empNumber(),
          u.fullName(),
          u.username(),
          u.status(),
          u.locked(),
          u.lockedUntil(),
          u.failedAttempts(),
          u.mustChangePassword(),
          u.passwordChangedAt(),
          u.roles(),
          u.authorities(),
          u.createdBy(),
          u.createdDate(),
          u.modifiedBy(),
          u.modifiedDate(),
          sessionsRevoked);
    }
  }

  public record UserAccountPage(List<UserAccount> content, PageMeta page) {}
}
