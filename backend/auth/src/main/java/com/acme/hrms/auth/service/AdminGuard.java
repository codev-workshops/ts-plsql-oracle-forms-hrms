package com.acme.hrms.auth.service;

import com.acme.hrms.admin.AdminSupport;
import com.acme.hrms.auth.repo.SessionRepository;
import com.acme.hrms.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Last-admin invariant (P5 §9.2): every mutation that can lower the number of {@code ACTIVE}
 * accounts holding {@code ADMIN:EDIT} serialises on one transaction-scoped advisory lock, applies
 * its write, then re-counts and fails with {@code -20805} when none is left. Also closes every
 * session of a user whose effective authorities changed.
 */
@Component
public class AdminGuard {

  static final String LOCK_KEY = "hrms.admin_edit_guard";

  private final AdminSupport support;
  private final SessionRepository sessions;
  private final SessionRevoker revoker;
  private final Clock clock;

  public AdminGuard(
      AdminSupport support, SessionRepository sessions, SessionRevoker revoker, Clock clock) {
    this.support = support;
    this.sessions = sessions;
    this.revoker = revoker;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void lock() {
    support
        .jdbc()
        .query("select pg_advisory_xact_lock(hashtext(:k))", Map.of("k", LOCK_KEY), rs -> {});
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireAdminEditHolder() {
    if (adminEditHolders() == 0) {
      throw AdminSupport.error(ErrorCode.LAST_ADMIN_EDIT_HOLDER, null);
    }
  }

  public int adminEditHolders() {
    return support.count(
        "select count(distinct ua.user_id) from user_accounts ua"
            + " join user_roles ur on ur.user_id = ua.user_id"
            + " join role_permissions rp on rp.role_id = ur.role_id"
            + " where ua.status = 'ACTIVE' and rp.authority = 'ADMIN:EDIT'",
        Map.of());
  }

  /** Closes every active session of the account; returns how many were closed. */
  @Transactional(propagation = Propagation.MANDATORY)
  public int revokeSessions(long userId) {
    Long empId =
        support
            .jdbc()
            .queryForObject(
                "select emp_id from user_accounts where user_id = :id",
                Map.of("id", userId),
                Long.class);
    if (empId == null) {
      return 0;
    }
    Instant now = clock.instant();
    List<Long> active = sessions.activeSessions(empId);
    for (long sessionId : active) {
      revoker.close(sessionId, now);
    }
    return active.size();
  }
}
