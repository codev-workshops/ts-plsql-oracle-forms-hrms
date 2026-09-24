package com.acme.hrms.validation.dto.auth;

import com.acme.hrms.validation.dto.employee.StrictRequest;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

/**
 * Body of POST/PUT /api/admin/roles (ROLES + ROLE_PERMISSIONS, auth-owned). roleCode immutable ->
 * -20801; seeded roles read-only -> -20802; maxGrade < minGrade -> -20603; permissions the caller
 * does not hold -> -20806. {@code permissions} is server-validated only: the v1 schema vocabulary
 * has no array type, so the exporter omits it.
 */
public class RoleRequest extends StrictRequest {

  @NotBlank
  @Size(min = 2, max = 30)
  @Pattern(regexp = AuthorityRules.ROLE_CODE_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Role code is required",
      patternMessage = AuthorityRules.ROLE_CODE_MESSAGE)
  private String roleCode;

  @NotBlank
  @Size(min = 1, max = 100)
  @FieldMeta(trim = true, requiredMessage = "Role name is required")
  private String roleName;

  @NotNull
  @Min(1)
  @Max(999)
  @FieldMeta(requiredMessage = "Minimum grade is required")
  private Integer minGrade;

  @NotNull
  @Min(1)
  @Max(999)
  @FieldMeta(requiredMessage = "Maximum grade is required")
  private Integer maxGrade;

  @NotEmpty
  private List<@NotBlank @Pattern(regexp = AuthorityRules.AUTHORITY_PATTERN) String> permissions;

  @AssertTrue(message = "maxGrade must be greater than or equal to minGrade")
  public boolean isGradeRangeValid() {
    return minGrade == null || maxGrade == null || maxGrade >= minGrade;
  }

  @AssertTrue(message = "permissions must be distinct")
  public boolean isPermissionsDistinct() {
    return permissions == null || new HashSet<>(permissions).size() == permissions.size();
  }

  public String getRoleCode() {
    return roleCode;
  }

  public void setRoleCode(String roleCode) {
    this.roleCode = roleCode == null || roleCode.isBlank() ? null : roleCode.trim();
  }

  public String getRoleName() {
    return roleName;
  }

  public void setRoleName(String roleName) {
    this.roleName = roleName == null || roleName.isBlank() ? null : roleName.trim();
  }

  public Integer getMinGrade() {
    return minGrade;
  }

  public void setMinGrade(Integer minGrade) {
    this.minGrade = minGrade;
  }

  public Integer getMaxGrade() {
    return maxGrade;
  }

  public void setMaxGrade(Integer maxGrade) {
    this.maxGrade = maxGrade;
  }

  public List<String> getPermissions() {
    return permissions;
  }

  public void setPermissions(List<String> permissions) {
    this.permissions = permissions;
  }
}
