package com.hrms.platform.auth;

import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.employee.EmployeeSummary;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.param.ParameterService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * USER_SESSIONS management. Timeout is idle-based using SECURITY.SESSION_TIMEOUT_MIN
 * (legacy used a hard-coded absolute 30 min, inventory §4.2).
 */
@Service
public class SessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final EmployeeLookup employees;
    private final ParameterService params;
    private final Clock clock;

    public SessionService(JdbcTemplate jdbc, EmployeeLookup employees, ParameterService params, Clock clock) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.params = params;
        this.clock = clock;
    }

    public String open(EmployeeSummary employee, String username, String ipAddress) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("""
                INSERT INTO user_sessions (emp_id, username, session_token, login_time, last_access_time,
                                           ip_address, forms_module, session_status)
                VALUES (?, ?, ?, ?, ?, ?, 'WEB', 'ACTIVE')
                """, employee.empId(), username, token, now, now, ipAddress);
        return token;
    }

    public CurrentUser resolve(String token) {
        record Row(long sessionId, long empId, String username, String status, LocalDateTime lastAccess) { }
        List<Row> rows = jdbc.query("""
                SELECT session_id, emp_id, username, session_status, coalesce(last_access_time, login_time)
                FROM user_sessions WHERE session_token = ?
                """, (rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDateTime.class)), token);
        Row row = rows.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_SESSION_EXPIRED, "Session not found"));
        if (!"ACTIVE".equals(row.status())) {
            throw new ApiException(ErrorCode.AUTH_SESSION_EXPIRED, "Session is no longer active");
        }
        int timeoutMinutes = params.getInt("SECURITY", "SESSION_TIMEOUT_MIN", 30);
        LocalDateTime now = LocalDateTime.now(clock);
        if (row.lastAccess().plusMinutes(timeoutMinutes).isBefore(now)) {
            jdbc.update("UPDATE user_sessions SET session_status = 'EXPIRED' WHERE session_id = ?", row.sessionId());
            throw new ApiException(ErrorCode.AUTH_SESSION_EXPIRED, "Session expired");
        }
        EmployeeSummary emp = employees.findById(row.empId())
                .filter(EmployeeSummary::isActiveEmployee)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_SESSION_EXPIRED, "Employee is not active"));
        jdbc.update("UPDATE user_sessions SET last_access_time = ? WHERE session_id = ?", now, row.sessionId());
        return new CurrentUser(emp.empId(), row.username(), emp.fullName(), emp.gradeLevel(), emp.managerEmpId(),
                row.sessionId());
    }

    public void close(long sessionId) {
        jdbc.update("UPDATE user_sessions SET session_status = 'CLOSED', logout_time = ? WHERE session_id = ?",
                LocalDateTime.now(clock), sessionId);
    }

    public Optional<Long> activeSessionCount(long empId) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT count(*) FROM user_sessions WHERE emp_id = ? AND session_status = 'ACTIVE'", Long.class,
                empId));
    }
}
