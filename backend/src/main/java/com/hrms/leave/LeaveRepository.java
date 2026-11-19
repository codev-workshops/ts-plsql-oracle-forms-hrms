package com.hrms.leave;

import com.hrms.leave.LeaveModels.LeaveBalance;
import com.hrms.leave.LeaveModels.LeaveRequest;
import com.hrms.leave.LeaveModels.LeaveType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class LeaveRepository {

    private static final String TYPE_SELECT = """
            SELECT leave_type_id, leave_type_code, leave_type_name, paid_flag, accrual_flag, accrual_rate,
                   accrual_frequency, max_balance, carryover_max, carryover_expiry, requires_approval,
                   min_tenure_days, active_flag
            FROM leave_types
            """;

    private static final String REQUEST_SELECT = """
            SELECT r.request_id, r.emp_id, e.first_name || ' ' || e.last_name AS employee_name, r.leave_type_id,
                   t.leave_type_code, t.leave_type_name, r.start_date, r.end_date, r.total_days, r.half_day_flag,
                   r.half_day_period, r.status, r.reason, r.approver_emp_id,
                   a.first_name || ' ' || a.last_name AS approver_name, r.approval_date, r.approval_comments,
                   r.cancel_reason, r.created_date
            FROM leave_requests r
            JOIN employees e ON e.emp_id = r.emp_id
            JOIN leave_types t ON t.leave_type_id = r.leave_type_id
            LEFT JOIN employees a ON a.emp_id = r.approver_emp_id
            """;

    private static final String BALANCE_SELECT = """
            SELECT b.leave_type_id, t.leave_type_code, t.leave_type_name, b.calendar_year, b.opening_balance,
                   b.accrued, b.used, b.adjustment, b.pending, b.available, b.carryover_from_prev,
                   b.carryover_expiry_dt
            FROM leave_balances b JOIN leave_types t ON t.leave_type_id = b.leave_type_id
            """;

    private final JdbcTemplate jdbc;

    public LeaveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Full leave-type row including batch fields. */
    public record TypeRow(LeaveType type, String accrualFrequency, Integer carryoverExpiryMonths, boolean active) { }

    public List<TypeRow> allTypes() {
        return jdbc.query(TYPE_SELECT + " ORDER BY leave_type_id", LeaveRepository::mapType);
    }

    public Optional<TypeRow> findType(int leaveTypeId) {
        return jdbc.query(TYPE_SELECT + " WHERE leave_type_id = ?", LeaveRepository::mapType, leaveTypeId)
                .stream().findFirst();
    }

    public Optional<LeaveRequest> findRequest(long requestId) {
        return jdbc.query(REQUEST_SELECT + " WHERE r.request_id = ?", LeaveRepository::mapRequest, requestId)
                .stream().findFirst();
    }

    public Optional<LeaveRequest> lockRequest(long requestId) {
        jdbc.query("SELECT request_id FROM leave_requests WHERE request_id = ? FOR UPDATE", rs -> { }, requestId);
        return findRequest(requestId);
    }

    public List<LeaveRequest> requestsForEmployee(long empId, String status) {
        if (status == null) {
            return jdbc.query(REQUEST_SELECT + " WHERE r.emp_id = ? ORDER BY r.start_date DESC, r.request_id DESC",
                    LeaveRepository::mapRequest, empId);
        }
        return jdbc.query(REQUEST_SELECT + " WHERE r.emp_id = ? AND r.status = ?"
                + " ORDER BY r.start_date DESC, r.request_id DESC", LeaveRepository::mapRequest, empId, status);
    }

    public List<LeaveRequest> pendingForApprover(long approverEmpId) {
        return jdbc.query(REQUEST_SELECT + " WHERE r.approver_emp_id = ? AND r.status = 'PENDING'"
                + " ORDER BY r.created_date, r.request_id", LeaveRepository::mapRequest, approverEmpId);
    }

    public List<LeaveRequest> teamCalendar(long managerEmpId, LocalDate start, LocalDate end) {
        return jdbc.query(REQUEST_SELECT + """
                 WHERE e.manager_emp_id = ? AND r.status IN ('APPROVED', 'TAKEN')
                   AND r.start_date <= ? AND r.end_date >= ?
                 ORDER BY r.start_date, employee_name
                """, LeaveRepository::mapRequest, managerEmpId, end, start);
    }

    public boolean overlaps(long empId, LocalDate start, LocalDate end, Long excludeRequestId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM leave_requests
                WHERE emp_id = ? AND status IN ('PENDING', 'APPROVED')
                  AND (?::bigint IS NULL OR request_id <> ?) AND start_date <= ? AND end_date >= ?
                """, Integer.class, empId, excludeRequestId, excludeRequestId, end, start);
        return count != null && count > 0;
    }

    public long insertRequest(long empId, int leaveTypeId, LocalDate start, LocalDate end, BigDecimal days,
                              boolean halfDay, String halfDayPeriod, String status, String reason,
                              Long approverEmpId, LocalDateTime approvalDate, String approvalComments, String user) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO leave_requests (emp_id, leave_type_id, start_date, end_date, total_days,
                        half_day_flag, half_day_period, status, reason, approver_emp_id, approval_date,
                        approval_comments, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"request_id"});
            ps.setLong(1, empId);
            ps.setInt(2, leaveTypeId);
            ps.setObject(3, start);
            ps.setObject(4, end);
            ps.setBigDecimal(5, days);
            ps.setString(6, halfDay ? "Y" : "N");
            ps.setString(7, halfDayPeriod);
            ps.setString(8, status);
            ps.setString(9, reason);
            ps.setObject(10, approverEmpId);
            ps.setObject(11, approvalDate);
            ps.setString(12, approvalComments);
            ps.setString(13, user);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateDecision(long requestId, String status, Long approverEmpId, LocalDateTime when,
                               String comments, String user) {
        jdbc.update("""
                UPDATE leave_requests SET status = ?, approver_emp_id = ?, approval_date = ?, approval_comments = ?,
                       modified_by = ?, modified_date = now()
                WHERE request_id = ?
                """, status, approverEmpId, when, comments, user, requestId);
    }

    public void updateCancelled(long requestId, String reason, LocalDateTime when, String user) {
        jdbc.update("""
                UPDATE leave_requests SET status = 'CANCELLED', cancel_reason = ?, cancelled_date = ?,
                       modified_by = ?, modified_date = now()
                WHERE request_id = ?
                """, reason, when, user, requestId);
    }

    public List<LeaveBalance> balances(long empId, int year) {
        return jdbc.query(BALANCE_SELECT + " WHERE b.emp_id = ? AND b.calendar_year = ? ORDER BY b.leave_type_id",
                LeaveRepository::mapBalance, empId, year);
    }

    public Optional<LeaveBalance> balance(long empId, int leaveTypeId, int year) {
        return jdbc.query(BALANCE_SELECT + " WHERE b.emp_id = ? AND b.leave_type_id = ? AND b.calendar_year = ?",
                LeaveRepository::mapBalance, empId, leaveTypeId, year).stream().findFirst();
    }

    /** Creates a zero balance row if missing (legacy silently skipped, inventory §9). */
    public int ensureBalance(long empId, int leaveTypeId, int year, String user) {
        return jdbc.update("""
                INSERT INTO leave_balances (emp_id, leave_type_id, calendar_year, created_by)
                VALUES (?, ?, ?, ?) ON CONFLICT (emp_id, leave_type_id, calendar_year) DO NOTHING
                """, empId, leaveTypeId, year, user);
    }

    /** Adds deltas to pending/used of one balance row (row must exist). */
    public void adjustBalance(long empId, int leaveTypeId, int year, BigDecimal pendingDelta, BigDecimal usedDelta,
                              String user) {
        jdbc.update("""
                UPDATE leave_balances SET pending = pending + ?, used = used + ?, modified_by = ?, modified_date = now()
                WHERE emp_id = ? AND leave_type_id = ? AND calendar_year = ?
                """, pendingDelta, usedDelta, user, empId, leaveTypeId, year);
    }

    private static TypeRow mapType(ResultSet rs, int row) throws SQLException {
        LeaveType type = new LeaveType(
                rs.getInt("leave_type_id"),
                rs.getString("leave_type_code"),
                rs.getString("leave_type_name"),
                "Y".equals(rs.getString("paid_flag")),
                "Y".equals(rs.getString("accrual_flag")),
                rs.getBigDecimal("accrual_rate"),
                rs.getBigDecimal("max_balance"),
                rs.getBigDecimal("carryover_max"),
                "Y".equals(rs.getString("requires_approval")),
                rs.getInt("min_tenure_days"));
        return new TypeRow(type, rs.getString("accrual_frequency"), rs.getObject("carryover_expiry", Integer.class),
                "Y".equals(rs.getString("active_flag")));
    }

    private static LeaveRequest mapRequest(ResultSet rs, int row) throws SQLException {
        return new LeaveRequest(
                rs.getLong("request_id"),
                rs.getLong("emp_id"),
                rs.getString("employee_name"),
                rs.getInt("leave_type_id"),
                rs.getString("leave_type_code"),
                rs.getString("leave_type_name"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBigDecimal("total_days"),
                "Y".equals(rs.getString("half_day_flag")),
                rs.getString("half_day_period"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getObject("approver_emp_id", Long.class),
                rs.getString("approver_name"),
                rs.getObject("approval_date", LocalDateTime.class),
                rs.getString("approval_comments"),
                rs.getString("cancel_reason"),
                rs.getObject("created_date", LocalDateTime.class));
    }

    private static LeaveBalance mapBalance(ResultSet rs, int row) throws SQLException {
        return new LeaveBalance(
                rs.getInt("leave_type_id"),
                rs.getString("leave_type_code"),
                rs.getString("leave_type_name"),
                rs.getInt("calendar_year"),
                rs.getBigDecimal("opening_balance"),
                rs.getBigDecimal("accrued"),
                rs.getBigDecimal("used"),
                rs.getBigDecimal("adjustment"),
                rs.getBigDecimal("pending"),
                rs.getBigDecimal("available"),
                rs.getBigDecimal("carryover_from_prev"),
                rs.getObject("carryover_expiry_dt", LocalDate.class));
    }
}
