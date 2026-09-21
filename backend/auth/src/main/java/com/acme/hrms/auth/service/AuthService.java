package com.acme.hrms.auth.service;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.auth.config.AuthProperties;
import com.acme.hrms.auth.jwt.JwtService;
import com.acme.hrms.auth.repo.RefreshTokenRepository;
import com.acme.hrms.auth.repo.RefreshTokenRepository.RefreshToken;
import com.acme.hrms.auth.repo.SessionRepository;
import com.acme.hrms.auth.repo.UserAccountRepository;
import com.acme.hrms.auth.repo.UserAccountRepository.Account;
import com.acme.hrms.auth.web.AuthDtos.CurrentUser;
import com.acme.hrms.auth.web.AuthDtos.TokenResponse;
import com.acme.hrms.common.error.EmployeeNotFoundException;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.error.InvalidCredentialsException;
import com.acme.hrms.common.error.RateLimitedException;
import com.acme.hrms.common.param.SystemParameterService;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.validation.password.PasswordPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PKG_SECURITY.authenticate / logout / is_session_valid / change_password re-implemented on
 * PostgreSQL. Deliberate divergences from the legacy package (contracts/p0-foundation/README.md):
 *
 * <ul>
 *   <li>SEC-01 MD5 → BCrypt via {@link PasswordEncoder}.
 *   <li>SEC-02 no lockout → {@link LoginRateLimiter} (5 / 15 min / username, 429).
 *   <li>SEC-05 password never verified → verified; every failure is one -20301.
 *   <li>SEC-07 hard-coded AES key → {@code FieldEncryptionService} with a configured key.
 *   <li>SEC-08 predictable session id → random {@code jti} + opaque refresh token.
 *   <li>SEC-10 ambiguous e-mail → treated as a failed login, unique index on lower(email).
 *   <li>DATA-04 username truncated to 30 → username = e-mail up to 100 chars.
 * </ul>
 */
@Service
public class AuthService {

  public static final String TABLE_USER_SESSIONS = "USER_SESSIONS";
  public static final String TABLE_USER_ACCOUNTS = "USER_ACCOUNTS";

  /** Login / refresh result: the body plus the refresh cookie value (never in the body). */
  public record TokenPair(TokenResponse response, String refreshToken, Instant refreshExpiresAt) {}

  private final UserAccountRepository accounts;
  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;
  private final JwtService jwt;
  private final PasswordEncoder passwordEncoder;
  private final LoginRateLimiter rateLimiter;
  private final SystemParameterService parameters;
  private final AuditService audit;
  private final AuthProperties props;
  private final SessionRevoker revoker;
  private final Clock clock;

  /** Hash of a random secret - only there to keep the unknown-user path constant-time. */
  private final String dummyHash;

  public AuthService(
      UserAccountRepository accounts,
      SessionRepository sessions,
      RefreshTokenRepository refreshTokens,
      JwtService jwt,
      PasswordEncoder passwordEncoder,
      LoginRateLimiter rateLimiter,
      SystemParameterService parameters,
      AuditService audit,
      AuthProperties props,
      SessionRevoker revoker,
      Clock clock) {
    this.accounts = accounts;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.jwt = jwt;
    this.passwordEncoder = passwordEncoder;
    this.rateLimiter = rateLimiter;
    this.parameters = parameters;
    this.audit = audit;
    this.props = props;
    this.revoker = revoker;
    this.clock = clock;
    this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
  }

  @Transactional
  public TokenPair login(String username, String password, @Nullable String clientIp) {
    OptionalLong retryAfter = rateLimiter.retryAfterSeconds(username);
    if (retryAfter.isPresent()) {
      throw new RateLimitedException(retryAfter.getAsLong());
    }
    Optional<Account> found = accounts.findByEmail(username);
    // Constant-cost path: always run one BCrypt verification so unknown users take as long as
    // wrong passwords (openapi.yaml: "same timing profile").
    String hash = found.map(Account::passwordHash).orElse(dummyHash);
    boolean passwordOk = passwordEncoder.matches(password, hash);
    if (found.isEmpty() || !passwordOk || !found.get().canLogin()) {
      rateLimiter.recordFailure(username);
      found.ifPresent(a -> accounts.recordFailure(a.userId()));
      throw new InvalidCredentialsException();
    }
    Account account = found.get();
    rateLimiter.reset(username);
    accounts.resetFailures(account.userId());

    Instant now = clock.instant();
    Set<String> roles = accounts.authorities(account.userId());
    int ttl = parameters.sessionTimeoutMinutes() * 60;
    JwtService.IssuedToken access =
        jwt.issue(String.valueOf(account.userId()), account.empId(), roles, ttl);
    long sessionId = sessions.open(account.empId(), account.email(), clientIp, access.jti(), now);
    String refresh = issueRefresh(account.userId(), sessionId, access.jti(), now);
    audit.log(
        TABLE_USER_SESSIONS,
        sessionId,
        AuditService.Action.LOGIN,
        null,
        "{\"ip\":" + json(clientIp) + "}",
        account.email(),
        clientIp,
        String.valueOf(sessionId));
    return new TokenPair(
        new TokenResponse(access.token(), TokenResponse.BEARER, ttl, currentUser(account, roles)),
        refresh,
        now.plus(props.refresh().ttl()));
  }

  @Transactional
  public TokenPair refresh(@Nullable String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new HrmsException(ErrorCode.TOKEN_INVALID);
    }
    Instant now = clock.instant();
    RefreshToken stored =
        refreshTokens
            .find(refreshToken)
            .orElseThrow(() -> new HrmsException(ErrorCode.TOKEN_INVALID));
    if (stored.revokedAt() != null) {
      // Replay of a rotated token: kill the whole session (token theft signal). Detached so the
      // kill is committed although this request ends in 401.
      revoker.closeDetached(stored.sessionId(), now);
      throw new HrmsException(ErrorCode.TOKEN_INVALID);
    }
    if (!stored.expiresAt().isAfter(now) || !sessions.isActive(stored.sessionId())) {
      throw new HrmsException(ErrorCode.TOKEN_INVALID);
    }
    Account account =
        accounts
            .findById(stored.userId())
            .filter(Account::canLogin)
            .orElseThrow(() -> new HrmsException(ErrorCode.TOKEN_INVALID));

    Set<String> roles = accounts.authorities(account.userId());
    int ttl = parameters.sessionTimeoutMinutes() * 60;
    JwtService.IssuedToken access =
        jwt.issue(String.valueOf(account.userId()), account.empId(), roles, ttl);
    sessions.rebind(stored.sessionId(), access.jti());
    String newRefresh = refreshTokens.newOpaqueToken();
    refreshTokens.revoke(stored.tokenHash(), now, RefreshTokenRepository.hash(newRefresh));
    refreshTokens.store(
        newRefresh,
        account.userId(),
        stored.sessionId(),
        access.jti(),
        now,
        now.plus(props.refresh().ttl()));
    return new TokenPair(
        new TokenResponse(access.token(), TokenResponse.BEARER, ttl, currentUser(account, roles)),
        newRefresh,
        now.plus(props.refresh().ttl()));
  }

  /** Idempotent: an already-closed session still yields success. */
  @Transactional
  public void logout(CallerIdentity caller, String accessToken, @Nullable String clientIp) {
    Instant now = clock.instant();
    sessions.revokeJti(caller.jti(), jwt.expiryOf(accessToken).orElse(now.plusSeconds(3600)));
    sessions
        .findActiveByJti(caller.jti())
        .ifPresent(
            sessionId -> {
              closeSession(sessionId, now);
              audit.log(
                  TABLE_USER_SESSIONS,
                  sessionId,
                  AuditService.Action.LOGOUT,
                  null,
                  null,
                  caller.userId(),
                  clientIp,
                  String.valueOf(sessionId));
            });
  }

  @Transactional(readOnly = true)
  public CurrentUser me(CallerIdentity caller) {
    Account account = requireActive(caller);
    return currentUser(account, accounts.authorities(account.userId()));
  }

  @Transactional
  public void changePassword(CallerIdentity caller, String currentPassword, String newPassword) {
    Account account = requireActive(caller);
    if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
      throw new InvalidCredentialsException("currentPassword");
    }
    PasswordPolicy.check(newPassword, parameters.passwordMinLength());
    if (newPassword.equals(currentPassword)) {
      throw new HrmsException(ErrorCode.PASSWORD_REUSED, "newPassword");
    }
    Instant now = clock.instant();
    accounts.updatePassword(
        account.userId(), passwordEncoder.encode(newPassword), now, account.email());

    long current = sessions.findActiveByJti(caller.jti()).orElse(-1L);
    for (long other : sessions.otherActiveSessions(account.empId(), current)) {
      closeSession(other, now);
    }
    audit.log(
        TABLE_USER_ACCOUNTS,
        account.userId(),
        AuditService.Action.UPDATE,
        "{\"passwordChanged\":false}",
        "{\"passwordChanged\":true}",
        account.email(),
        null,
        current < 0 ? null : String.valueOf(current));
  }

  private Account requireActive(CallerIdentity caller) {
    return accounts
        .findById(Long.parseLong(caller.userId()))
        .filter(a -> a.empId() == caller.empId() && a.isEmployeeActive())
        .orElseThrow(EmployeeNotFoundException::new);
  }

  private void closeSession(long sessionId, Instant now) {
    revoker.close(sessionId, now);
  }

  private String issueRefresh(long userId, long sessionId, String jti, Instant now) {
    String token = refreshTokens.newOpaqueToken();
    refreshTokens.store(token, userId, sessionId, jti, now, now.plus(props.refresh().ttl()));
    return token;
  }

  static CurrentUser currentUser(Account a, Set<String> roles) {
    return new CurrentUser(
        String.valueOf(a.userId()),
        a.empId(),
        a.empNumber(),
        a.email(),
        a.firstName(),
        a.lastName(),
        a.displayName(),
        a.deptId(),
        a.jobTitle(),
        roles,
        a.mustChangePassword());
  }

  private static String json(@Nullable String s) {
    return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
  }
}
