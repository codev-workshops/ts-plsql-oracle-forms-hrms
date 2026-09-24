package com.acme.hrms.auth.service;

import com.acme.hrms.admin.AdminSupport;
import com.acme.hrms.audit.AuditService;
import com.acme.hrms.auth.web.RoleAdminDtos.Role;
import com.acme.hrms.auth.web.RoleAdminDtos.RoleWriteResult;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.validation.dto.auth.RoleRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auth-owned maintenance of {@code roles} / {@code role_permissions} (P5 §9.2). Seeded roles 1–3
 * are read-only; ids come from {@code seq_role}; the caller may only hand out authorities it holds.
 */
@Service
public class RoleAdminService {

  static final String TABLE_ROLES = "ROLES";
  static final int LAST_SEEDED_ROLE_ID = 3;

  /** Exactly the authorities the backend references in {@code @PreAuthorize}. */
  static final List<String> AUTHORITIES;

  static {
    Set<String> v = new TreeSet<>();
    for (String module : List.of("PAYROLL", "EMPLOYEE", "LEAVE", "ADMIN", "REPORTS")) {
      for (String action : List.of("VIEW", "EDIT", "APPROVE", "CREATE")) {
        v.add(module + ":" + action);
      }
    }
    v.add("LEAVE:ADMIN");
    v.add("LEAVE:VIEW_ALL");
    for (String action : List.of("VIEW", "EDIT", "APPROVE", "CREATE", "ADMIN")) {
      v.add("PERFORMANCE:" + action);
    }
    AUTHORITIES = List.copyOf(v);
  }

  private static final String SELECT =
      "select r.role_id, r.role_code, r.role_name, r.min_grade, r.max_grade, r.created_by,"
          + " r.created_date,"
          + " (select count(*) from user_roles ur where ur.role_id = r.role_id) as user_count"
          + " from roles r";

  private final AdminSupport support;
  private final AdminGuard guard;

  public RoleAdminService(AdminSupport support, AdminGuard guard) {
    this.support = support;
    this.guard = guard;
  }

  public List<String> authorities() {
    return AUTHORITIES;
  }

  @Transactional(readOnly = true)
  public List<Role> list() {
    List<Role> roles = new ArrayList<>();
    for (Map<String, Object> row :
        support.jdbc().queryForList(SELECT + " order by r.role_id", Map.of())) {
      roles.add(toRole(row));
    }
    return roles;
  }

  @Transactional(readOnly = true)
  public Role get(int roleId) {
    return find(roleId);
  }

  @Transactional
  public Role create(RoleRequest r) {
    requireKnownAuthorities(r.getPermissions());
    requireCallerHolds(r.getPermissions(), "permissions");
    if (support.count(
            "select count(*) from roles where upper(role_code) = upper(:c)",
            Map.of("c", r.getRoleCode()))
        > 0) {
      throw AdminSupport.error(ErrorCode.ROLE_CODE_CONFLICT, "roleCode", r.getRoleCode());
    }
    Integer id =
        support.jdbc().queryForObject("select nextval('seq_role')", Map.of(), Integer.class);
    Map<String, Object> p = new HashMap<>();
    p.put("id", id);
    p.put("code", r.getRoleCode());
    p.put("name", r.getRoleName());
    p.put("min", r.getMinGrade());
    p.put("max", r.getMaxGrade());
    p.put("by", support.actor());
    p.put("now", support.now());
    try {
      support
          .jdbc()
          .update(
              "insert into roles (role_id, role_code, role_name, min_grade, max_grade, created_by,"
                  + " created_date) values (:id, :code, :name, :min, :max, :by, :now)",
              p);
    } catch (DuplicateKeyException e) {
      throw AdminSupport.error(ErrorCode.ROLE_CODE_CONFLICT, "roleCode", r.getRoleCode());
    }
    insertPermissions(id, new LinkedHashSet<>(r.getPermissions()));
    Role created = find(id);
    support.audit(TABLE_ROLES, id, AuditService.Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public RoleWriteResult update(int roleId, RoleRequest r) {
    guard.lock();
    Role old = find(roleId);
    if (old.seeded()) {
      requireSeededUnchanged(old, r);
      return RoleWriteResult.of(old, 0);
    }
    if (!old.roleCode().equals(r.getRoleCode())) {
      throw new HrmsException(
          ErrorCode.ROLE_CODE_CONFLICT, "Role code is immutable: " + old.roleCode(), "roleCode");
    }
    requireKnownAuthorities(r.getPermissions());
    Set<String> wanted = new LinkedHashSet<>(r.getPermissions());
    Set<String> current = new LinkedHashSet<>(old.permissions());
    Set<String> added = new LinkedHashSet<>(wanted);
    added.removeAll(current);
    Set<String> removed = new LinkedHashSet<>(current);
    removed.removeAll(wanted);
    requireCallerHolds(added, "permissions");

    Map<String, Object> p = new HashMap<>();
    p.put("id", roleId);
    p.put("name", r.getRoleName());
    p.put("min", r.getMinGrade());
    p.put("max", r.getMaxGrade());
    support
        .jdbc()
        .update(
            "update roles set role_name = :name, min_grade = :min, max_grade = :max"
                + " where role_id = :id",
            p);
    if (!removed.isEmpty()) {
      support
          .jdbc()
          .update(
              "delete from role_permissions where role_id = :id and authority in (:a)",
              Map.of("id", roleId, "a", removed));
    }
    insertPermissions(roleId, added);
    guard.requireAdminEditHolder();

    int revoked = 0;
    if (!added.isEmpty() || !removed.isEmpty()) {
      for (Long userId :
          support
              .jdbc()
              .queryForList(
                  "select user_id from user_roles where role_id = :id",
                  Map.of("id", roleId),
                  Long.class)) {
        revoked += guard.revokeSessions(userId);
      }
    }
    Role updated = find(roleId);
    support.audit(TABLE_ROLES, roleId, AuditService.Action.UPDATE, old, updated);
    return RoleWriteResult.of(updated, revoked);
  }

  @Transactional
  public void delete(int roleId) {
    Role old = find(roleId);
    if (old.seeded()) {
      throw AdminSupport.error(ErrorCode.ROLE_SEEDED, null, old.roleCode());
    }
    if (old.userCount() > 0) {
      throw AdminSupport.error(ErrorCode.ROLE_IN_USE, null, old.roleCode(), old.userCount());
    }
    support.jdbc().update("delete from role_permissions where role_id = :id", Map.of("id", roleId));
    support.jdbc().update("delete from roles where role_id = :id", Map.of("id", roleId));
    support.audit(TABLE_ROLES, roleId, AuditService.Action.DELETE, old, null);
  }

  private Role find(int roleId) {
    List<Map<String, Object>> rows =
        support.jdbc().queryForList(SELECT + " where r.role_id = :id", Map.of("id", roleId));
    if (rows.isEmpty()) {
      throw AdminSupport.error(ErrorCode.ROLE_NOT_FOUND, null, roleId);
    }
    return toRole(rows.get(0));
  }

  private Role toRole(Map<String, Object> row) {
    int id = ((Number) row.get("role_id")).intValue();
    List<String> perms =
        support
            .jdbc()
            .queryForList(
                "select authority from role_permissions where role_id = :id order by authority",
                Map.of("id", id),
                String.class);
    return new Role(
        id,
        (String) row.get("role_code"),
        (String) row.get("role_name"),
        ((Number) row.get("min_grade")).intValue(),
        ((Number) row.get("max_grade")).intValue(),
        perms,
        id <= LAST_SEEDED_ROLE_ID,
        ((Number) row.get("user_count")).intValue(),
        (String) row.get("created_by"),
        ((java.sql.Timestamp) row.get("created_date")).toLocalDateTime());
  }

  private void insertPermissions(int roleId, Set<String> authorities) {
    for (String a : authorities) {
      support
          .jdbc()
          .update(
              "insert into role_permissions (role_id, authority) values (:id, :a)",
              Map.of("id", roleId, "a", a));
    }
  }

  private static void requireSeededUnchanged(Role old, RoleRequest r) {
    boolean same =
        old.roleCode().equals(r.getRoleCode())
            && old.roleName().equals(r.getRoleName())
            && old.minGrade() == r.getMinGrade()
            && old.maxGrade() == r.getMaxGrade()
            && new TreeSet<>(old.permissions()).equals(new TreeSet<>(r.getPermissions()));
    if (!same) {
      throw AdminSupport.error(ErrorCode.ROLE_SEEDED, null, old.roleCode());
    }
  }

  static void requireKnownAuthorities(List<String> permissions) {
    for (String a : permissions) {
      if (!AUTHORITIES.contains(a)) {
        throw new HrmsException(
            ErrorCode.VALIDATION_FAILED, "Unknown authority: " + a, "permissions");
      }
    }
  }

  static void requireCallerHolds(Iterable<String> authorities, String field) {
    Set<String> held = CurrentCaller.require().authorities();
    for (String a : authorities) {
      if (!held.contains(a)) {
        throw AdminSupport.error(ErrorCode.PRIVILEGE_EXCEEDS_CALLER, field, a);
      }
    }
  }
}
