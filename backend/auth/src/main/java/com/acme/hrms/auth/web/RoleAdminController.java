package com.acme.hrms.auth.web;

import com.acme.hrms.admin.AdminValidation;
import com.acme.hrms.auth.service.RoleAdminService;
import com.acme.hrms.auth.web.RoleAdminDtos.Role;
import com.acme.hrms.auth.web.RoleAdminDtos.RoleWriteResult;
import com.acme.hrms.validation.dto.auth.RoleRequest;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code admin-roles}: authorities and roles. Served by auth, the owner of the role tables. */
@RestController
@Validated
public class RoleAdminController {

  private final RoleAdminService roles;
  private final AdminValidation validation;

  public RoleAdminController(RoleAdminService roles, AdminValidation validation) {
    this.roles = roles;
    this.validation = validation;
  }

  @GetMapping("/api/admin/authorities")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public List<String> authorities() {
    return roles.authorities();
  }

  @GetMapping("/api/admin/roles")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public List<Role> list() {
    return roles.list();
  }

  @PostMapping("/api/admin/roles")
  @PreAuthorize("hasAuthority('ADMIN:EDIT')")
  public ResponseEntity<Role> create(@RequestBody(required = false) @Nullable RoleRequest body) {
    Role r = roles.create(validation.validate(body));
    return ResponseEntity.created(URI.create("/api/admin/roles/" + r.roleId())).body(r);
  }

  @GetMapping("/api/admin/roles/{roleId}")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public Role get(@PathVariable @Min(1) int roleId) {
    return roles.get(roleId);
  }

  @PutMapping("/api/admin/roles/{roleId}")
  @PreAuthorize("hasAuthority('ADMIN:EDIT')")
  public RoleWriteResult update(
      @PathVariable @Min(1) int roleId, @RequestBody(required = false) @Nullable RoleRequest body) {
    return roles.update(roleId, validation.validate(body));
  }

  @DeleteMapping("/api/admin/roles/{roleId}")
  @PreAuthorize("hasAuthority('ADMIN:EDIT')")
  public ResponseEntity<Void> delete(@PathVariable @Min(1) int roleId) {
    roles.delete(roleId);
    return ResponseEntity.noContent().build();
  }
}
