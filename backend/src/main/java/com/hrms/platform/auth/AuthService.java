package com.hrms.platform.auth;

import com.hrms.platform.audit.AuditService;
import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.employee.EmployeeSummary;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.param.ParameterService;
import com.hrms.platform.validation.Validators;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Login against USER_CREDENTIALS with bcrypt (open question 1; legacy never checked passwords). */
@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final EmployeeLookup employees;
    private final SessionService sessions;
    private final ParameterService params;
    private final AuditService audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(JdbcTemplate jdbc, EmployeeLookup employees, SessionService sessions,
                       ParameterService params, AuditService audit) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.sessions = sessions;
        this.params = params;
        this.audit = audit;
    }

    public record LoginResult(String token, CurrentUser user) { }

    private record Credential(long empId, String username, String hash, int failedAttempts, boolean locked) { }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String username, String password, String ipAddress) {
        String normalized = username == null ? "" : username.trim().toLowerCase();
        List<Credential> found = jdbc.query("""
                SELECT emp_id, username, password_hash, failed_attempts, locked_flag
                FROM user_credentials WHERE lower(username) = ?
                """, (rs, i) -> new Credential(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                "Y".equals(rs.getString(5))), normalized);
        Credential cred = found.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid username or password"));
        if (cred.locked()) {
            throw new ApiException(ErrorCode.AUTH_ACCOUNT_LOCKED, "Account is locked");
        }
        if (password == null || !encoder.matches(password, cred.hash())) {
            int maxFailed = params.getInt("SECURITY", "MAX_FAILED_LOGINS", 5);
            jdbc.update("""
                    UPDATE user_credentials SET failed_attempts = failed_attempts + 1,
                           locked_flag = CASE WHEN failed_attempts + 1 >= ? THEN 'Y' ELSE 'N' END
                    WHERE emp_id = ?
                    """, maxFailed, cred.empId());
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid username or password");
        }
        EmployeeSummary emp = employees.findById(cred.empId())
                .filter(EmployeeSummary::isActiveEmployee)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid username or password"));
        jdbc.update("UPDATE user_credentials SET failed_attempts = 0 WHERE emp_id = ?", cred.empId());
        String token = sessions.open(emp, cred.username(), ipAddress);
        audit.record("USER_SESSIONS", emp.empId(), AuditService.Action.INSERT, null,
                Map.of("event", "LOGIN", "username", cred.username()), cred.username());
        CurrentUser user = sessions.resolve(token);
        return new LoginResult(token, user);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logout(CurrentUser user) {
        sessions.close(user.sessionId());
        audit.record("USER_SESSIONS", user.empId(), AuditService.Action.UPDATE, null,
                Map.of("event", "LOGOUT"), user.username());
    }

    @Transactional
    public void changePassword(CurrentUser user, String currentPassword, String newPassword) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_credentials WHERE emp_id = ?",
                String.class, user.empId());
        if (currentPassword == null || !encoder.matches(currentPassword, hash)) {
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Current password is incorrect");
        }
        int minLength = params.getInt("SECURITY", "PASSWORD_MIN_LENGTH", 8);
        Validators.passwordPolicyViolation(newPassword, minLength).ifPresent(code -> {
            throw switch (code) {
                case "-20310" -> new ApiException(ErrorCode.PASSWORD_TOO_SHORT,
                        "Password must be at least " + minLength + " characters");
                case "-20311" -> new ApiException(ErrorCode.PASSWORD_NO_UPPERCASE,
                        "Password must contain an uppercase letter");
                default -> new ApiException(ErrorCode.PASSWORD_NO_DIGIT, "Password must contain a digit");
            };
        });
        jdbc.update("""
                UPDATE user_credentials SET password_hash = ?, password_changed_at = now(), modified_by = ?,
                       modified_date = now() WHERE emp_id = ?
                """, encoder.encode(newPassword), user.username(), user.empId());
        audit.record("USER_CREDENTIALS", user.empId(), AuditService.Action.UPDATE, null,
                Map.of("event", "PASSWORD_CHANGE"), user.username());
    }
}
