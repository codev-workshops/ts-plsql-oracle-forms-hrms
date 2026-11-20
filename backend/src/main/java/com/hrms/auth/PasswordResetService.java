package com.hrms.auth;

import com.hrms.common.ApiException;
import com.hrms.common.AuditWriter;
import com.hrms.common.ErrorCode;
import com.hrms.notification.NotificationService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-time password reset links (OQ-15, contract v1.5). Legacy never verified passwords, so there is nothing
 * to migrate: the cutover gives every ACTIVE employee an unusable credential and a reset link through the
 * notification outbox; HR can issue a new link for one employee (e.g. a new hire). Only the SHA-256 of a
 * token is stored; a token works once, until it expires or a newer one supersedes it.
 */
@Service
public class PasswordResetService {

    static final String TABLE = "PASSWORD_RESET_TOKENS";
    static final String SUBJECT = "HRMS password setup";
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditWriter audit;
    private final NotificationService notifications;
    private final Clock clock;
    private final PasswordResetProperties props;
    private final TransactionTemplate tx;

    public PasswordResetService(JdbcClient jdbc, PasswordEncoder encoder, AuditWriter audit,
            NotificationService notifications, Clock clock, PasswordResetProperties props, TransactionTemplate tx) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
        this.props = props;
    }

    public record IssuedReset(long empId, OffsetDateTime expiresAt) {
    }

    public record InitialResets(int issued, int skippedWithoutEmail) {
    }

    private record Target(long empId, String email, String firstName, String status, boolean active) {
    }

    private record Token(long tokenId, long empId, OffsetDateTime expiresAt, OffsetDateTime usedAt,
            String employmentStatus, boolean active, String email) {
    }

    /** HR issues (or re-issues) a reset link for one ACTIVE employee; older unused links stop working. */
    @Transactional
    public IssuedReset issue(long empId, String actor, String ipAddress, Long sessionId) {
        Target t = jdbc.sql("""
                SELECT emp_id, email, first_name, employment_status, active_flag FROM hrms.employees
                WHERE emp_id = :empId
                """).param("empId", empId)
                .query((rs, i) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5)))
                .optional().orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));
        if (!"ACTIVE".equals(t.status()) || !t.active()) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ACTIVE, "Employee is not active", null);
        }
        if (t.email() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Employee has no email address");
        }
        return issueToken(t, actor, ipAddress, sessionId, now());
    }

    /**
     * The cutover step (OQ-15): every ACTIVE employee without a credential gets an unusable one
     * ({@code reset_required}) and a reset link. Repeatable: employees that already have a credential are
     * skipped, so a re-run issues nothing new.
     */
    @Transactional
    public InitialResets issueInitialResets(String actor) {
        OffsetDateTime now = now();
        List<Target> targets = jdbc.sql("""
                SELECT e.emp_id, e.email, e.first_name, e.employment_status, e.active_flag FROM hrms.employees e
                WHERE e.employment_status = 'ACTIVE' AND e.active_flag
                  AND NOT EXISTS (SELECT 1 FROM hrms.user_credentials c WHERE c.emp_id = e.emp_id)
                ORDER BY e.emp_id
                """).query((rs, i) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5))).list();
        // Nobody knows this secret, so the placeholder hash can never match a password.
        String unusable = encoder.encode(Tokens.newToken());
        int issued = 0;
        int skipped = 0;
        for (Target t : targets) {
            if (t.email() == null) {
                skipped++;
                continue;
            }
            jdbc.sql("""
                    INSERT INTO hrms.user_credentials (emp_id, password_hash, reset_required, created_by, created_date)
                    VALUES (:empId, :hash, true, :actor, :now)
                    """).param("empId", t.empId()).param("hash", unusable).param("actor", actor).param("now", now)
                    .update();
            audit.write("USER_CREDENTIALS", t.empId(), "INSERT", null, Map.of("resetRequired", true), actor, null,
                    null);
            issueToken(t, actor, null, null, now);
            issued++;
        }
        return new InitialResets(issued, skipped);
    }

    /** Sets the password from a valid token, then closes every session of the employee. */
    public void confirm(String token, String newPassword, String ipAddress) {
        PasswordPolicy.check(newPassword);
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > AuthService.MAX_PASSWORD_BYTES) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY, "Password must be at most 72 bytes");
        }
        String hash = encoder.encode(newPassword);
        tx.executeWithoutResult(status -> apply(token, hash, ipAddress));
    }

    private void apply(String token, String hash, String ipAddress) {
        OffsetDateTime now = now();
        Optional<Token> found = jdbc.sql("""
                SELECT t.token_id, t.emp_id, t.expires_at, t.used_at, e.employment_status, e.active_flag, e.email
                FROM hrms.password_reset_tokens t JOIN hrms.employees e ON e.emp_id = t.emp_id
                WHERE t.token_hash = :hash FOR UPDATE OF t
                """).param("hash", Tokens.hash(token))
                .query((rs, i) -> new Token(rs.getLong(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class),
                        rs.getObject(4, OffsetDateTime.class), rs.getString(5), rs.getBoolean(6), rs.getString(7)))
                .optional();
        if (found.isEmpty() || found.get().usedAt() != null || !now.isBefore(found.get().expiresAt())
                || !"ACTIVE".equals(found.get().employmentStatus()) || !found.get().active()) {
            throw new ApiException(ErrorCode.INVALID_RESET_TOKEN, "The reset link is invalid or has expired");
        }
        Token t = found.get();
        String actor = t.email() == null ? "system:password-reset" : t.email().toLowerCase(Locale.ROOT);
        jdbc.sql("""
                INSERT INTO hrms.user_credentials (emp_id, password_hash, password_changed_date, created_by, created_date)
                VALUES (:empId, :hash, :now, :actor, :now)
                ON CONFLICT (emp_id) DO UPDATE SET password_hash = EXCLUDED.password_hash, reset_required = false,
                    failed_attempts = 0, locked_until = NULL, active_flag = true,
                    password_changed_date = EXCLUDED.password_changed_date, modified_by = :actor, modified_date = :now
                """).param("empId", t.empId()).param("hash", hash).param("now", now).param("actor", actor).update();
        jdbc.sql("UPDATE hrms.password_reset_tokens SET used_at = :now WHERE token_id = :id")
                .param("now", now).param("id", t.tokenId()).update();
        // The link is spent: keep it out of the outbox from now on.
        jdbc.sql("""
                UPDATE hrms.notification_queue SET body = '[reset link used]'
                WHERE reference_table = :table AND reference_id = :id
                """).param("table", TABLE).param("id", t.tokenId()).update();
        jdbc.sql("""
                UPDATE hrms.user_sessions SET session_status = 'CLOSED', logout_time = :now
                WHERE emp_id = :empId AND session_status = 'ACTIVE'
                """).param("now", now).param("empId", t.empId()).update();
        audit.write("USER_CREDENTIALS", t.empId(), "UPDATE", null, Map.of("passwordReset", true, "tokenId",
                t.tokenId()), actor, ipAddress, null);
    }

    private IssuedReset issueToken(Target t, String actor, String ipAddress, Long sessionId, OffsetDateTime now) {
        jdbc.sql("UPDATE hrms.password_reset_tokens SET used_at = :now WHERE emp_id = :empId AND used_at IS NULL")
                .param("now", now).param("empId", t.empId()).update();
        String token = Tokens.newToken();
        OffsetDateTime expiresAt = now.plus(props.tokenTtl());
        long tokenId = jdbc.sql("""
                INSERT INTO hrms.password_reset_tokens (emp_id, token_hash, expires_at, created_by, created_date)
                VALUES (:empId, :hash, :expiresAt, :actor, :now)
                RETURNING token_id
                """).param("empId", t.empId()).param("hash", Tokens.hash(token)).param("expiresAt", expiresAt)
                .param("actor", actor).param("now", now).query(Long.class).single();
        audit.write(TABLE, tokenId, "INSERT", null, Map.of("empId", t.empId(), "expiresAt", expiresAt.toString()),
                actor, ipAddress, sessionId);
        notifications.queueEmail(t.empId(), SUBJECT, "Hello " + t.firstName() + ",\n\n"
                + "Set your HRMS password with this one-time link (valid until " + EXPIRY.format(expiresAt) + "):\n"
                + props.linkBase() + token + "\n\nIf you did not expect this message, contact HR.",
                TABLE, tokenId, actor);
        return new IssuedReset(t.empId(), expiresAt);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
