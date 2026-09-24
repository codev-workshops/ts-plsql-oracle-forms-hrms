package com.acme.hrms.auth.sso;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC implementation against the legacy Oracle schema. Registered only when {@code
 * hrms.legacy.oracle.url} is set (see {@link SsoBridgeConfig}); otherwise the bridge answers 502
 * SSO_LEGACY_UNAVAILABLE. Only PKG_SECURITY.authenticate / logout and two plain SQL statements are
 * used - no PL/SQL is added to the target.
 */
public class OracleLegacySessionGateway implements LegacySessionGateway {

  private static final Logger log = LoggerFactory.getLogger(OracleLegacySessionGateway.class);

  private final DataSource oracle;

  public OracleLegacySessionGateway(DataSource oracle) {
    this.oracle = oracle;
  }

  @Override
  public Optional<LegacyEmployee> findActiveEmployee(long empId) {
    String sql =
        "select emp_id, email from employees where emp_id = ? and employment_status = 'ACTIVE'"
            + " and active_flag = 'Y'";
    try (Connection c = oracle.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, empId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && rs.getString("email") != null) {
          return Optional.of(new LegacyEmployee(rs.getLong("emp_id"), rs.getString("email")));
        }
        return Optional.empty();
      }
    } catch (SQLException e) {
      throw new LegacyUnavailableException("Oracle employee lookup failed", e);
    }
  }

  @Override
  public long openFormsSession(String email, String clientIp, String formsModule, String jti) {
    try (Connection c = oracle.getConnection()) {
      c.setAutoCommit(false);
      long sessionId;
      try (CallableStatement cs =
          c.prepareCall("{ ? = call pkg_security.authenticate(?, ?, ?) }")) {
        cs.registerOutParameter(1, Types.NUMERIC);
        cs.setString(2, email);
        cs.setString(3, "<ignored>"); // SEC-05: the legacy procedure never checks it
        cs.setString(4, clientIp);
        cs.execute();
        sessionId = cs.getLong(1);
      }
      if (sessionId <= 0) {
        c.rollback();
        throw new LegacyUnavailableException(
            "PKG_SECURITY.authenticate returned " + sessionId, null);
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "update user_sessions set forms_module = ?, jwt_jti = ? where session_id = ?")) {
        ps.setString(1, formsModule);
        ps.setString(2, jti);
        ps.setLong(3, sessionId);
        ps.executeUpdate();
      }
      c.commit();
      return sessionId;
    } catch (SQLException e) {
      throw new LegacyUnavailableException("Oracle Forms session could not be opened", e);
    }
  }

  @Override
  public void closeFormsSession(String jti) {
    try (Connection c = oracle.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "update user_sessions set session_status = 'CLOSED', logout_time = sysdate"
                    + " where jwt_jti = ? and session_status = 'ACTIVE'")) {
      ps.setString(1, jti);
      ps.executeUpdate();
    } catch (SQLException e) {
      log.warn("legacy session close failed for jti={}", jti, e);
    }
  }
}
