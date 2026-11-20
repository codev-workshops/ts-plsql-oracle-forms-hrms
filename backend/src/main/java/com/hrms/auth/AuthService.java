package com.hrms.auth;

import com.hrms.common.ApiException;
import com.hrms.common.AuditWriter;
import com.hrms.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replaces PKG_SECURITY.authenticate / logout / is_session_valid / change_password (D-06).
 * Fix-not-port: the password is actually verified (legacy never checked it), BCrypt replaces MD5,
 * failures are uniform and timing-safe, and consecutive failures lock the credential (OQ-07).
 */
@Service
public class AuthService {

    /** BCrypt only reads the first 72 bytes; longer input is rejected rather than truncated. */
    static final int MAX_PASSWORD_BYTES = 72;

    private static final String INVALID = "Invalid username or password";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final PermissionService permissions;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AuthProperties props;
    private final String dummyHash;

    public AuthService(JdbcClient jdbc, PasswordEncoder encoder, PermissionService permissions, AuditWriter audit,
            TransactionTemplate tx, Clock clock, AuthProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.permissions = permissions;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.dummyHash = encoder.encode("timing-equaliser-" + Tokens.newToken());
    }

    public record LoginResult(String token, HrmsPrincipal principal) {
    }

    private record Credential(long credentialId, String hash, int failedAttempts, OffsetDateTime lockedUntil) {
    }

    private record Session(long sessionId, long empId, String username, OffsetDateTime loginTime,
            OffsetDateTime expiresAt, String status, String employmentStatus) {
    }

    public LoginResult login(String username, String password, String ipAddress) {
        String login = username.trim().toLowerCase(Locale.ROOT);
        boolean tooLong = password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
        LoginResult result = tx.execute(status -> {
            OffsetDateTime now = now();
            Optional<Long> empId = resolveActiveEmployee(login);
            Optional<Credential> cred = empId.flatMap(this::lockCredential);
            boolean matches = !tooLong && encoder.matches(password, cred.map(Credential::hash).orElse(dummyHash));
            if (cred.isEmpty()) {
                return null;
            }
            Credential c = cred.get();
            boolean locked = c.lockedUntil() != null && now.isBefore(c.lockedUntil());
            if (locked) {
                return null;
            }
            if (!matches) {
                recordFailure(c, now);
                return null;
            }
            jdbc.sql("""
                    UPDATE hrms.user_credentials SET failed_attempts = 0, locked_until = NULL
                    WHERE credential_id = :id AND (failed_attempts <> 0 OR locked_until IS NOT NULL)
                    """).param("id", c.credentialId()).update();
            return openSession(empId.get(), login, ipAddress, now);
        });
        if (result == null) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID);
        }
        return result;
    }

    /** Legacy picks MIN(emp_id) when two ACTIVE employees share an email (OQ-06). */
    private Optional<Long> resolveActiveEmployee(String login) {
        return jdbc.sql("""
                SELECT min(emp_id) FROM hrms.employees
                WHERE lower(email) = :login AND employment_status = 'ACTIVE'
                """).param("login", login).query(Long.class).optional();
    }

    private Optional<Credential> lockCredential(long empId) {
        return jdbc.sql("""
                SELECT credential_id, password_hash, failed_attempts, locked_until FROM hrms.user_credentials
                WHERE emp_id = :empId AND active_flag FOR UPDATE
                """).param("empId", empId)
                .query((rs, n) -> new Credential(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getObject(4, OffsetDateTime.class)))
                .optional();
    }

    private void recordFailure(Credential c, OffsetDateTime now) {
        int attempts = (c.lockedUntil() != null ? 0 : c.failedAttempts()) + 1;
        OffsetDateTime lockedUntil = attempts >= props.maxFailedAttempts() ? now.plus(props.lockDuration()) : null;
        jdbc.sql("""
                UPDATE hrms.user_credentials SET failed_attempts = :attempts, locked_until = :lockedUntil
                WHERE credential_id = :id
                """)
                .param("attempts", lockedUntil == null ? attempts : 0)
                .param("lockedUntil", lockedUntil)
                .param("id", c.credentialId())
                .update();
    }

    private LoginResult openSession(long empId, String login, String ipAddress, OffsetDateTime now) {
        String token = Tokens.newToken();
        OffsetDateTime expiresAt = now.plus(props.sessionTimeout());
        long sessionId = jdbc.sql("""
                INSERT INTO hrms.user_sessions (emp_id, username, login_time, ip_address, session_status,
                                                created_date, token_hash, expires_at)
                VALUES (:empId, :username, :now, :ip, 'ACTIVE', :now, :hash, :expiresAt)
                RETURNING session_id
                """)
                .param("empId", empId).param("username", login).param("now", now).param("ip", ipAddress)
                .param("hash", Tokens.hash(token)).param("expiresAt", expiresAt)
                .query(Long.class).single();
        audit.write("USER_SESSIONS", sessionId, "INSERT", null,
                Map.of("emp_id", empId, "session_status", "ACTIVE"), login, ipAddress, sessionId);
        return new LoginResult(token, new HrmsPrincipal(empId, login, sessionId, now, expiresAt,
                permissions.permissionsOf(empId)));
    }

    /**
     * Validates a bearer token (replaces is_session_valid): the session must be ACTIVE, not past its
     * absolute expiry (an expired session is marked EXPIRED, as legacy did) and its employee ACTIVE.
     */
    public Optional<HrmsPrincipal> authenticate(String token) {
        Optional<Session> found = jdbc.sql("""
                SELECT s.session_id, s.emp_id, s.username, s.login_time, s.expires_at, s.session_status,
                       e.employment_status
                FROM hrms.user_sessions s JOIN hrms.employees e ON e.emp_id = s.emp_id
                WHERE s.token_hash = :hash
                """).param("hash", Tokens.hash(token))
                .query((rs, n) -> new Session(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class),
                        rs.getString(6), rs.getString(7)))
                .optional();
        if (found.isEmpty() || !"ACTIVE".equals(found.get().status())) {
            return Optional.empty();
        }
        Session s = found.get();
        OffsetDateTime now = now();
        if (!now.isBefore(s.expiresAt())) {
            jdbc.sql("""
                    UPDATE hrms.user_sessions SET session_status = 'EXPIRED', logout_time = :now
                    WHERE session_id = :id AND session_status = 'ACTIVE'
                    """).param("now", now).param("id", s.sessionId()).update();
            return Optional.empty();
        }
        if (!"ACTIVE".equals(s.employmentStatus())) {
            return Optional.empty();
        }
        return Optional.of(new HrmsPrincipal(s.empId(), s.username(), s.sessionId(), s.loginTime(), s.expiresAt(),
                permissions.permissionsOf(s.empId())));
    }

    public void logout(HrmsPrincipal principal, String ipAddress) {
        tx.executeWithoutResult(status -> {
            int closed = jdbc.sql("""
                    UPDATE hrms.user_sessions SET session_status = 'CLOSED', logout_time = :now
                    WHERE session_id = :id AND session_status = 'ACTIVE'
                    """).param("now", now()).param("id", principal.sessionId()).update();
            if (closed == 1) {
                audit.write("USER_SESSIONS", principal.sessionId(), "UPDATE", Map.of("session_status", "ACTIVE"),
                        Map.of("session_status", "CLOSED"), principal.actor(), ipAddress, principal.sessionId());
            }
        });
    }

    /**
     * Implements PKG_SECURITY.change_password for real (the legacy body was a stub). On success the
     * caller's other ACTIVE sessions are closed; the current one stays valid.
     */
    public void changePassword(HrmsPrincipal principal, String currentPassword, String newPassword, String ipAddress) {
        PasswordPolicy.check(newPassword);
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY, "Password must be at most 72 bytes");
        }
        boolean changed = Boolean.TRUE.equals(tx.execute(status -> {
            Optional<Credential> cred = lockCredential(principal.empId());
            boolean tooLong = currentPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
            if (cred.isEmpty() || tooLong || !encoder.matches(currentPassword, cred.get().hash())) {
                return false;
            }
            OffsetDateTime now = now();
            jdbc.sql("""
                    UPDATE hrms.user_credentials SET password_hash = :hash, password_changed_date = :now,
                           failed_attempts = 0, locked_until = NULL, modified_by = :actor, modified_date = :now
                    WHERE credential_id = :id
                    """)
                    .param("hash", encoder.encode(newPassword)).param("now", now)
                    .param("actor", principal.actor()).param("id", cred.get().credentialId())
                    .update();
            jdbc.sql("""
                    UPDATE hrms.user_sessions SET session_status = 'CLOSED', logout_time = :now
                    WHERE emp_id = :empId AND session_status = 'ACTIVE' AND session_id <> :current
                    """).param("now", now).param("empId", principal.empId()).param("current", principal.sessionId())
                    .update();
            audit.write("USER_CREDENTIALS", principal.empId(), "UPDATE", null, Map.of("password_changed", true),
                    principal.actor(), ipAddress, principal.sessionId());
            return true;
        }));
        if (!changed) {
            throw new ApiException(ErrorCode.INVALID_CURRENT_PASSWORD, "Current password is incorrect");
        }
    }

    private OffsetDateTime now() {
        // PostgreSQL keeps microseconds; truncating keeps Java-side comparisons exact.
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
