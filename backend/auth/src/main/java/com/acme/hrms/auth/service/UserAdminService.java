package com.acme.hrms.auth.service;

import com.acme.hrms.admin.AdminDtos.PageMeta;
import com.acme.hrms.admin.AdminSupport;
import com.acme.hrms.audit.AuditService;
import com.acme.hrms.auth.web.RoleAdminDtos.UserAccount;
import com.acme.hrms.auth.web.RoleAdminDtos.UserAccountPage;
import com.acme.hrms.auth.web.RoleAdminDtos.UserAccountWriteResult;
import com.acme.hrms.auth.web.RoleAdminDtos.UserRoleGrant;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.validation.dto.auth.UserRolesRequest;
import com.acme.hrms.validation.dto.auth.UserStatusRequest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auth-owned maintenance of {@code user_roles} / {@code user_accounts.status} (P5 §9.2). Never
 * self-service; least privilege on every authority granted; last-admin guard under the common
 * advisory lock; all sessions of the target are revoked after a successful change.
 */
@Service
public class UserAdminService {

  static final String TABLE_USER_ACCOUNTS = "USER_ACCOUNTS";
  static final String TABLE_USER_ROLES = "USER_ROLES";

  private static final String SELECT =
      "select u.user_id, u.emp_id, e.emp_number, e.first_name, e.last_name, u.username,"
          + " u.status, u.locked_until, u.failed_attempts, u.must_change_password,"
          + " u.password_changed_at, u.created_by, u.created_date, u.modified_by, u.modified_date"
          + " from user_accounts u join employees e on e.emp_id = u.emp_id";

  private final AdminSupport support;
  private final AdminGuard guard;

  public UserAdminService(AdminSupport support, AdminGuard guard) {
    this.support = support;
    this.guard = guard;
  }

  @Transactional(readOnly = true)
  public UserAccountPage search(
      @Nullable String q,
      @Nullable String status,
      @Nullable Integer roleId,
      @Nullable Boolean locked,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where 1 = 1");
    Map<String, Object> p = new HashMap<>();
    if (q != null && !q.isBlank()) {
      where.append(
          " and (lower(u.username) like :q or lower(e.emp_number) like :q"
              + " or lower(e.first_name) like :q or lower(e.last_name) like :q)");
      p.put("q", "%" + q.trim().toLowerCase() + "%");
    }
    if (status != null) {
      where.append(" and u.status = :status");
      p.put("status", status);
    }
    if (roleId != null) {
      where.append(
          " and exists (select 1 from user_roles ur where ur.user_id = u.user_id"
              + " and ur.role_id = :role)");
      p.put("role", roleId);
    }
    if (locked != null) {
      where.append(
          locked
              ? " and u.locked_until > :now"
              : " and (u.locked_until is null or u.locked_until <= :now)");
      p.put("now", support.now());
    }
    long total =
        support.count(
            "select count(*) from user_accounts u join employees e on e.emp_id = u.emp_id" + where,
            p);
    p.put("limit", size);
    p.put("offset", (long) page * size);
    List<UserAccount> content = new ArrayList<>();
    for (Map<String, Object> row :
        support
            .jdbc()
            .queryForList(
                SELECT + where + " order by u.username, u.user_id limit :limit offset :offset",
                p)) {
      content.add(toAccount(row));
    }
    return new UserAccountPage(content, PageMeta.of(page, size, total));
  }

  @Transactional(readOnly = true)
  public UserAccount get(long userId) {
    return find(userId);
  }

  @Transactional
  public UserAccountWriteResult replaceRoles(long userId, UserRolesRequest r) {
    guard.lock();
    UserAccount old = find(userId);
    requireNotSelf(userId);
    Set<Integer> wanted = new LinkedHashSet<>(r.getRoleIds());
    for (Integer id : wanted) {
      if (support.count("select count(*) from roles where role_id = :id", Map.of("id", id)) == 0) {
        throw AdminSupport.error(ErrorCode.UNKNOWN_ROLE, "roleIds", id);
      }
    }
    Set<String> newAuthorities =
        new LinkedHashSet<>(
            support
                .jdbc()
                .queryForList(
                    "select distinct authority from role_permissions where role_id in (:ids)",
                    Map.of("ids", wanted),
                    String.class));
    Set<String> granted = new LinkedHashSet<>(newAuthorities);
    granted.removeAll(old.authorities());
    RoleAdminService.requireCallerHolds(granted, "roleIds");

    Set<Integer> current = new LinkedHashSet<>();
    for (UserRoleGrant g : old.roles()) {
      current.add(g.roleId());
    }
    Set<Integer> removed = new LinkedHashSet<>(current);
    removed.removeAll(wanted);
    Set<Integer> added = new LinkedHashSet<>(wanted);
    added.removeAll(current);
    if (!removed.isEmpty()) {
      support
          .jdbc()
          .update(
              "delete from user_roles where user_id = :u and role_id in (:ids)",
              Map.of("u", userId, "ids", removed));
    }
    for (Integer id : added) {
      Map<String, Object> p = new HashMap<>();
      p.put("u", userId);
      p.put("r", id);
      p.put("by", support.actor());
      p.put("now", support.now());
      support
          .jdbc()
          .update(
              "insert into user_roles (user_id, role_id, granted_by, granted_date)"
                  + " values (:u, :r, :by, :now)",
              p);
    }
    guard.requireAdminEditHolder();

    int revoked = added.isEmpty() && removed.isEmpty() ? 0 : guard.revokeSessions(userId);
    UserAccount updated = find(userId);
    support.audit(
        TABLE_USER_ROLES,
        userId,
        AuditService.Action.UPDATE,
        Map.of("roleIds", current),
        Map.of("roleIds", wanted));
    return UserAccountWriteResult.of(updated, revoked);
  }

  @Transactional
  public UserAccountWriteResult setStatus(long userId, UserStatusRequest r) {
    guard.lock();
    UserAccount old = find(userId);
    requireNotSelf(userId);
    Map<String, Object> p = new HashMap<>();
    p.put("id", userId);
    p.put("status", r.getStatus());
    p.put("by", support.actor());
    p.put("now", support.now());
    if ("ACTIVE".equals(r.getStatus())) {
      support
          .jdbc()
          .update(
              "update user_accounts set status = :status, locked_until = null,"
                  + " failed_attempts = 0, modified_by = :by, modified_date = :now"
                  + " where user_id = :id",
              p);
    } else {
      support
          .jdbc()
          .update(
              "update user_accounts set status = :status, modified_by = :by,"
                  + " modified_date = :now where user_id = :id",
              p);
    }
    guard.requireAdminEditHolder();

    int revoked = guard.revokeSessions(userId);
    UserAccount updated = find(userId);
    Map<String, Object> newValues = new HashMap<>();
    newValues.put("status", r.getStatus());
    newValues.put("reason", r.getReason());
    support.audit(
        TABLE_USER_ACCOUNTS,
        userId,
        AuditService.Action.STATUS_CHANGE,
        Map.of("status", old.status()),
        newValues);
    return UserAccountWriteResult.of(updated, revoked);
  }

  private void requireNotSelf(long userId) {
    if (String.valueOf(userId).equals(support.actor())) {
      throw AdminSupport.error(ErrorCode.SELF_ACCOUNT_MODIFICATION, null);
    }
  }

  private UserAccount find(long userId) {
    List<Map<String, Object>> rows =
        support.jdbc().queryForList(SELECT + " where u.user_id = :id", Map.of("id", userId));
    if (rows.isEmpty()) {
      throw AdminSupport.error(ErrorCode.USER_NOT_FOUND, null, userId);
    }
    return toAccount(rows.get(0));
  }

  private UserAccount toAccount(Map<String, Object> row) {
    long userId = ((Number) row.get("user_id")).longValue();
    List<UserRoleGrant> roles = new ArrayList<>();
    for (Map<String, Object> g :
        support
            .jdbc()
            .queryForList(
                "select ur.role_id, r.role_code, r.role_name, ur.granted_by, ur.granted_date"
                    + " from user_roles ur join roles r on r.role_id = ur.role_id"
                    + " where ur.user_id = :id order by ur.role_id",
                Map.of("id", userId))) {
      roles.add(
          new UserRoleGrant(
              ((Number) g.get("role_id")).intValue(),
              (String) g.get("role_code"),
              (String) g.get("role_name"),
              (String) g.get("granted_by"),
              ts(g.get("granted_date"))));
    }
    List<String> authorities =
        support
            .jdbc()
            .queryForList(
                "select distinct rp.authority from user_roles ur"
                    + " join role_permissions rp on rp.role_id = ur.role_id"
                    + " where ur.user_id = :id order by rp.authority",
                Map.of("id", userId),
                String.class);
    LocalDateTime lockedUntil = ts(row.get("locked_until"));
    return new UserAccount(
        userId,
        ((Number) row.get("emp_id")).longValue(),
        (String) row.get("emp_number"),
        row.get("first_name") + " " + row.get("last_name"),
        (String) row.get("username"),
        (String) row.get("status"),
        lockedUntil != null && lockedUntil.isAfter(support.now()),
        lockedUntil,
        ((Number) row.get("failed_attempts")).intValue(),
        (Boolean) row.get("must_change_password"),
        ts(row.get("password_changed_at")),
        roles,
        authorities,
        (String) row.get("created_by"),
        ts(row.get("created_date")),
        (String) row.get("modified_by"),
        ts(row.get("modified_date")));
  }

  @Nullable
  private static LocalDateTime ts(@Nullable Object v) {
    return v == null ? null : ((Timestamp) v).toLocalDateTime();
  }
}
