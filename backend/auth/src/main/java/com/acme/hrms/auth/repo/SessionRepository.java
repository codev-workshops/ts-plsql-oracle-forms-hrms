package com.acme.hrms.auth.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/**
 * user_sessions on PostgreSQL (audit / parallel-run parity with PKG_SECURITY.authenticate) plus the
 * access-token revocation list (revoked_jti). Owned by auth-service.
 */
@Repository
public class SessionRepository {

  private final JdbcTemplate jdbc;

  public SessionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long open(long empId, String username, @Nullable String ip, String jti, Instant now) {
    Long id = jdbc.queryForObject("select nextval('seq_user_session')", Long.class);
    jdbc.update(
        "insert into user_sessions (session_id, emp_id, username, login_time, ip_address,"
            + " session_status, jwt_jti) values (?, ?, ?, ?, ?, 'ACTIVE', ?)",
        id,
        empId,
        username,
        ts(now),
        ip,
        jti);
    return id;
  }

  public Optional<Long> findActiveByJti(String jti) {
    return jdbc
        .queryForList(
            "select session_id from user_sessions where jwt_jti = ? and session_status = 'ACTIVE'",
            Long.class,
            jti)
        .stream()
        .findFirst();
  }

  public boolean isActive(long sessionId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from user_sessions where session_id = ? and session_status = 'ACTIVE'",
            Integer.class,
            sessionId);
    return n != null && n > 0;
  }

  /** Rotation: the new access token's jti takes over the session row. */
  public void rebind(long sessionId, String jti) {
    jdbc.update("update user_sessions set jwt_jti = ? where session_id = ?", jti, sessionId);
  }

  public boolean close(long sessionId, Instant now) {
    return jdbc.update(
            "update user_sessions set session_status = 'CLOSED', logout_time = ?"
                + " where session_id = ? and session_status = 'ACTIVE'",
            ts(now),
            sessionId)
        > 0;
  }

  /** All ACTIVE sessions of an employee except one (password change keeps the current one). */
  public List<Long> otherActiveSessions(long empId, long keepSessionId) {
    return jdbc.queryForList(
        "select session_id from user_sessions where emp_id = ? and session_status = 'ACTIVE'"
            + " and session_id <> ?",
        Long.class,
        empId,
        keepSessionId);
  }

  public Optional<String> jtiOf(long sessionId) {
    return jdbc
        .queryForList(
            "select jwt_jti from user_sessions where session_id = ?", String.class, sessionId)
        .stream()
        .filter(j -> j != null)
        .findFirst();
  }

  public void revokeJti(String jti, Instant expiresAt) {
    jdbc.update(
        "insert into revoked_jti (jti, expires_at) values (?, ?) on conflict (jti) do nothing",
        jti,
        ts(expiresAt));
  }

  public boolean isJtiRevoked(String jti) {
    Integer n =
        jdbc.queryForObject("select count(*) from revoked_jti where jti = ?", Integer.class, jti);
    return n != null && n > 0;
  }

  static LocalDateTime ts(Instant i) {
    return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
