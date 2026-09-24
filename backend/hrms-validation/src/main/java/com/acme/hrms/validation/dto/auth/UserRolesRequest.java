package com.acme.hrms.validation.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.List;

/**
 * Body of PUT /api/admin/users/{userId}/roles (USER_ROLES set replacement, auth-owned). Unknown id
 * -> -20807; self -> -20804; least privilege -> -20806; last ADMIN:EDIT -> -20805. {@code roleIds}
 * is server-validated only: the v1 schema vocabulary has no array type, so the exporter omits it.
 */
public class UserRolesRequest {

  @NotEmpty private List<@NotNull @Min(1) Integer> roleIds;

  @AssertTrue(message = "roleIds must be distinct")
  public boolean isRoleIdsDistinct() {
    return roleIds == null || new HashSet<>(roleIds).size() == roleIds.size();
  }

  public List<Integer> getRoleIds() {
    return roleIds;
  }

  public void setRoleIds(List<Integer> roleIds) {
    this.roleIds = roleIds;
  }
}
