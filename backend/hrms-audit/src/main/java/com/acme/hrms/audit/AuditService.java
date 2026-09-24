package com.acme.hrms.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PKG_AUDIT.log_change replacement. Writes {@code audit_log} in the caller's transaction by default
 * (so a failed business write leaves no orphan row) and offers {@link #logDetached} for the
 * autonomous-transaction case (login/logout of a request that may still roll back).
 */
@Service
public class AuditService {

  public enum Action {
    INSERT,
    UPDATE,
    DELETE,
    STATUS_CHANGE,
    LOGIN,
    LOGOUT,
    PERIOD_CLOSE,
    PAYROLL_APPROVE,
    PAYROLL_REVERSE,
    REGISTER_DOWNLOAD
  }

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public AuditService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long log(
      String tableName,
      long recordId,
      Action action,
      @Nullable String oldValues,
      @Nullable String newValues,
      String changedBy,
      @Nullable String ipAddress,
      @Nullable String sessionId) {
    return insert(
        tableName, recordId, action, oldValues, newValues, changedBy, ipAddress, sessionId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long logDetached(
      String tableName,
      long recordId,
      Action action,
      @Nullable String oldValues,
      @Nullable String newValues,
      String changedBy,
      @Nullable String ipAddress,
      @Nullable String sessionId) {
    return insert(
        tableName, recordId, action, oldValues, newValues, changedBy, ipAddress, sessionId);
  }

  private long insert(
      String tableName,
      long recordId,
      Action action,
      @Nullable String oldValues,
      @Nullable String newValues,
      String changedBy,
      @Nullable String ipAddress,
      @Nullable String sessionId) {
    long id = jdbc.queryForObject("select nextval('seq_audit')", Long.class);
    jdbc.update(
        "insert into audit_log (audit_id, table_name, record_id, action_type, old_values,"
            + " new_values, changed_by, changed_date, ip_address, session_id) values"
            + " (?,?,?,?,?,?,?,?,?,?)",
        id,
        tableName.toUpperCase(),
        recordId,
        action.name(),
        oldValues,
        newValues,
        changedBy,
        Timestamp.valueOf(LocalDateTime.now(clock)),
        ipAddress,
        sessionId);
    return id;
  }
}
