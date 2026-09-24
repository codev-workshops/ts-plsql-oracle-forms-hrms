package com.acme.hrms.auth.web;

import com.acme.hrms.admin.AdminValidation;
import com.acme.hrms.auth.service.UserAdminService;
import com.acme.hrms.auth.web.RoleAdminDtos.UserAccount;
import com.acme.hrms.auth.web.RoleAdminDtos.UserAccountPage;
import com.acme.hrms.auth.web.RoleAdminDtos.UserAccountWriteResult;
import com.acme.hrms.validation.dto.auth.UserRolesRequest;
import com.acme.hrms.validation.dto.auth.UserStatusRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code admin-roles}: user accounts, their role set and status. Served by auth. */
@RestController
@Validated
public class UserAdminController {

  private final UserAdminService users;
  private final AdminValidation validation;

  public UserAdminController(UserAdminService users, AdminValidation validation) {
    this.users = users;
    this.validation = validation;
  }

  @GetMapping("/api/admin/users")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public UserAccountPage search(
      @RequestParam(required = false) @Nullable @Size(min = 1, max = 100) String q,
      @RequestParam(required = false) @Nullable @Pattern(regexp = "^(ACTIVE|DISABLED)$")
          String status,
      @RequestParam(required = false) @Nullable @Min(1) Integer roleId,
      @RequestParam(required = false) @Nullable Boolean locked,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    return users.search(q, status, roleId, locked, page, size);
  }

  @GetMapping("/api/admin/users/{userId}")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public UserAccount get(@PathVariable @Min(1) long userId) {
    return users.get(userId);
  }

  @PutMapping("/api/admin/users/{userId}/roles")
  @PreAuthorize("hasAuthority('ADMIN:EDIT')")
  public UserAccountWriteResult replaceRoles(
      @PathVariable @Min(1) long userId,
      @RequestBody(required = false) @Nullable UserRolesRequest body) {
    return users.replaceRoles(userId, validation.validate(body));
  }

  @PutMapping("/api/admin/users/{userId}/status")
  @PreAuthorize("hasAuthority('ADMIN:EDIT')")
  public UserAccountWriteResult setStatus(
      @PathVariable @Min(1) long userId,
      @RequestBody(required = false) @Nullable UserStatusRequest body) {
    return users.setStatus(userId, validation.validate(body));
  }
}
