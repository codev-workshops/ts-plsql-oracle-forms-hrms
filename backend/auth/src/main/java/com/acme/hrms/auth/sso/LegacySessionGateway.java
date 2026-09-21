package com.acme.hrms.auth.sso;

import java.util.Optional;

/**
 * The one and only permitted caller of {@code PKG_SECURITY.authenticate} on Oracle (SEC-05). Reads
 * the employee e-mail for an emp_id, opens the Forms session and stamps it with module + jti
 * (CUTOVER_PLAN.md §4.2 item 0.6 additive column).
 */
public interface LegacySessionGateway {

  record LegacyEmployee(long empId, String email) {}

  /** Empty when the employee does not exist or is not ACTIVE on Oracle. */
  Optional<LegacyEmployee> findActiveEmployee(long empId);

  /**
   * @return Oracle {@code USER_SESSIONS.SESSION_ID}
   * @throws LegacyUnavailableException when Oracle is unreachable or authenticate returned -1
   */
  long openFormsSession(String email, String clientIp, String formsModule, String jti);

  /** Best effort: close the Oracle session bound to this jti (API-side logout). */
  void closeFormsSession(String jti);

  class LegacyUnavailableException extends RuntimeException {
    public LegacyUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
