package com.acme.hrms.auth.service;

import com.acme.hrms.auth.repo.RefreshTokenRepository;
import com.acme.hrms.auth.repo.SessionRepository;
import com.acme.hrms.common.param.SystemParameterService;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes a session, revokes its refresh tokens and its current access-token jti. Runs in its own
 * transaction so the kill survives when the caller subsequently fails the request (refresh-token
 * replay must end in 401 *and* leave the session dead).
 */
@Component
public class SessionRevoker {

  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;
  private final SystemParameterService parameters;

  public SessionRevoker(
      SessionRepository sessions,
      RefreshTokenRepository refreshTokens,
      SystemParameterService parameters) {
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.parameters = parameters;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void closeDetached(long sessionId, Instant now) {
    close(sessionId, now);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void close(long sessionId, Instant now) {
    sessions.close(sessionId, now);
    refreshTokens.revokeAllForSession(sessionId, now);
    sessions
        .jtiOf(sessionId)
        .ifPresent(
            j -> sessions.revokeJti(j, now.plusSeconds(parameters.sessionTimeoutMinutes() * 60L)));
  }
}
