package com.hrms.leave;

import com.hrms.auth.Action;
import com.hrms.auth.HrmsPrincipal;
import com.hrms.auth.Module;
import com.hrms.common.ApiException;
import com.hrms.common.AuditWriter;
import com.hrms.common.ErrorCode;
import com.hrms.leave.LeaveModels.HalfDayPeriod;
import com.hrms.leave.LeaveModels.LeaveBalance;
import com.hrms.leave.LeaveModels.LeaveRequest;
import com.hrms.leave.LeaveModels.LeaveStatus;
import com.hrms.leave.LeaveModels.LeaveType;
import com.hrms.leave.LeaveModels.SubmitLeaveRequest;
import com.hrms.leave.LeaveModels.TeamCalendarEntry;
import com.hrms.notification.NotificationService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave pilot domain service: PKG_LEAVE submit / approve / reject / cancel (PKG-L01, PKG-L02), the Forms
 * gates re-homed server-side (FRM-050..056), status audit (TRG-010, D-08) and notifications via the
 * outbox (PKG-N01). Row scoping (OQ-09): employees act on their own requests; approvals go to the
 * assigned approver, or to holders of LEAVE:APPROVE; nobody decides their own request.
 */
@Service
public class LeaveService {

    static final String TABLE = "LEAVE_REQUESTS";
    static final int BACKDATE_LIMIT_DAYS = 5;
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final BigDecimal HALF_DAY = new BigDecimal("0.5");

    private static final String SELECT_REQUEST = """
            SELECT r.request_id, r.emp_id, e.first_name || ' ' || e.last_name AS employee_name, r.leave_type_id,
                   t.leave_type_code, t.leave_type_name, r.start_date, r.end_date, r.total_days, r.half_day_flag,
                   r.half_day_period, r.status, r.reason, r.approver_emp_id, r.approval_date, r.approval_comments,
                   r.cancel_reason, r.cancelled_date, r.created_date
            FROM hrms.leave_requests r
            JOIN hrms.employees e ON e.emp_id = r.emp_id
            JOIN hrms.leave_types t ON t.leave_type_id = r.leave_type_id
            """;

    private final JdbcClient jdbc;
    private final BusinessDays businessDays;
    private final NotificationService notifications;
    private final AuditWriter audit;
    private final Clock clock;

    public LeaveService(JdbcClient jdbc, BusinessDays businessDays, NotificationService notifications,
            AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.businessDays = businessDays;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    /** Caller context for audit rows. */
    public record Caller(HrmsPrincipal principal, String ipAddress) {
    }

    private record Employee(long empId, String firstName, String lastName, LocalDate hireDate, String locationCode,
            Long managerEmpId, String employmentStatus) {
    }

    private record TypeRow(long id, String name, boolean accrual, Integer minTenureDays, boolean requiresApproval) {
    }

    private record Locked(long requestId, long empId, long leaveTypeId, LocalDate startDate, LocalDate endDate,
            BigDecimal totalDays, String status, Long approverEmpId) {
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<LeaveType> activeTypes() {
        return jdbc.sql("""
                SELECT leave_type_id, leave_type_code, leave_type_name, paid_flag, accrual_flag, accrual_rate,
                       accrual_frequency, max_balance, min_tenure_days, requires_approval, requires_document
                FROM hrms.leave_types WHERE active_flag ORDER BY leave_type_id
                """).query((rs, i) -> new LeaveType(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                rs.getBoolean(5), rs.getBigDecimal(6), rs.getString(7), rs.getBigDecimal(8),
                rs.getObject(9, Integer.class), rs.getBoolean(10), rs.getBoolean(11))).list();
    }

    @Transactional(readOnly = true)
    public List<LeaveBalance> balances(long empId, int year) {
        return jdbc.sql("""
                SELECT b.leave_type_id, t.leave_type_code, t.leave_type_name, b.calendar_year, b.opening_balance,
                       b.accrued, b.used, b.adjustment, b.pending, b.available, b.carryover_from_prev,
                       b.carryover_expiry_dt
                FROM hrms.leave_balances b JOIN hrms.leave_types t ON t.leave_type_id = b.leave_type_id
                WHERE b.emp_id = :empId AND b.calendar_year = :year
                ORDER BY b.leave_type_id
                """).param("empId", empId).param("year", year)
                .query((rs, i) -> new LeaveBalance(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11),
                        rs.getObject(12, LocalDate.class)))
                .list();
    }

    /** FRM-050: the employee's own requests, newest first. */
    @Transactional(readOnly = true)
    public List<LeaveRequest> requestsOf(long empId, LeaveStatus status, Integer year) {
        return jdbc.sql(SELECT_REQUEST + """
                WHERE r.emp_id = :empId
                  AND (CAST(:status AS varchar) IS NULL OR r.status = :status)
                  AND (CAST(:year AS integer) IS NULL OR EXTRACT(YEAR FROM r.start_date) = :year)
                ORDER BY r.start_date DESC, r.request_id DESC
                """).param("empId", empId).param("status", status == null ? null : status.name())
                .param("year", year).query(LeaveService::request).list();
    }

    /** FRM-056 / get_pending_requests: PENDING requests assigned to the approver, oldest first. */
    @Transactional(readOnly = true)
    public List<LeaveRequest> pendingApprovals(long approverEmpId) {
        return jdbc.sql(SELECT_REQUEST + """
                WHERE r.status = 'PENDING' AND r.approver_emp_id = :approver
                ORDER BY r.created_date, r.request_id
                """).param("approver", approverEmpId).query(LeaveService::request).list();
    }

    /** FRM-056 / get_team_calendar: APPROVED or TAKEN leave of the manager's direct reports in the window. */
    @Transactional(readOnly = true)
    public List<TeamCalendarEntry> teamCalendar(long managerEmpId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ApiException(ErrorCode.INVALID_DATE_RANGE, "from must be before or equal to to");
        }
        return jdbc.sql("""
                SELECT r.request_id, e.emp_id, e.first_name || ' ' || e.last_name, t.leave_type_code,
                       t.leave_type_name, r.start_date, r.end_date, r.total_days, r.status, r.half_day_flag,
                       r.half_day_period
                FROM hrms.leave_requests r
                JOIN hrms.employees e ON e.emp_id = r.emp_id
                JOIN hrms.leave_types t ON t.leave_type_id = r.leave_type_id
                WHERE e.manager_emp_id = :manager AND r.status IN ('APPROVED', 'TAKEN')
                  AND r.start_date <= :to AND r.end_date >= :from
                ORDER BY r.start_date, e.last_name, r.request_id
                """).param("manager", managerEmpId).param("from", from).param("to", to)
                .query((rs, i) -> new TeamCalendarEntry(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), rs.getBigDecimal(8), LeaveStatus.valueOf(rs.getString(9)),
                        rs.getBoolean(10), period(rs.getString(11))))
                .list();
    }

    /** One request, visible to its owner, its assigned approver and LEAVE:APPROVE holders; else 404. */
    @Transactional(readOnly = true)
    public LeaveRequest get(long requestId, HrmsPrincipal p) {
        LeaveRequest r = find(requestId);
        boolean visible = r.empId() == p.empId() || (r.approverEmpId() != null && r.approverEmpId() == p.empId())
                || p.has(Module.LEAVE, Action.APPROVE);
        if (!visible) {
            throw notFound();
        }
        return r;
    }

    // ---------------------------------------------------------------- submit (PKG-L01)

    @Transactional
    public LeaveRequest submit(Caller caller, SubmitLeaveRequest body) {
        long empId = caller.principal().empId();
        jdbc.sql("SELECT pg_advisory_xact_lock(3, CAST(:empId AS integer))").param("empId", empId)
                .query((rs, i) -> 1).list();

        Employee emp = jdbc.sql("""
                SELECT emp_id, first_name, last_name, hire_date, location_code, manager_emp_id, employment_status
                FROM hrms.employees WHERE emp_id = :empId
                """).param("empId", empId)
                .query((rs, i) -> new Employee(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5), rs.getObject(6, Long.class), rs.getString(7)))
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found: " + empId));
        if (!"ACTIVE".equals(emp.employmentStatus())) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ACTIVE, "Employee not found or not active: " + empId);
        }

        TypeRow type = jdbc.sql("""
                SELECT leave_type_id, leave_type_name, accrual_flag, min_tenure_days, requires_approval
                FROM hrms.leave_types WHERE leave_type_id = :id AND active_flag
                """).param("id", body.leaveTypeId())
                .query((rs, i) -> new TypeRow(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                        rs.getObject(4, Integer.class), rs.getBoolean(5)))
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_LEAVE_TYPE,
                        "Invalid leave type: " + body.leaveTypeId()));

        LocalDate today = LocalDate.now(clock);
        if (type.minTenureDays() != null && type.minTenureDays() > 0
                && ChronoUnit.DAYS.between(emp.hireDate(), today) < type.minTenureDays()) {
            throw new ApiException(ErrorCode.MIN_TENURE_NOT_MET, "Minimum tenure of " + type.minTenureDays()
                    + " days not met for leave type: " + type.name());
        }

        LocalDate start = body.startDate();
        LocalDate end = body.endDate();
        if (start.isAfter(end)) {
            throw new ApiException(ErrorCode.INVALID_DATE_RANGE, "Start date must be before or equal to end date");
        }
        if (ChronoUnit.DAYS.between(start, today) > BACKDATE_LIMIT_DAYS) {
            throw new ApiException(ErrorCode.BACKDATE_LIMIT_EXCEEDED,
                    "Cannot submit leave requests more than 5 days in the past");
        }

        // DEF-01 fix: a half day names its period (FRM-055) and covers exactly one day.
        boolean halfDay = Boolean.TRUE.equals(body.halfDay());
        HalfDayPeriod period = body.halfDayPeriod();
        if (halfDay && period == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "halfDayPeriod (AM or PM) is required for a half day");
        }
        if (halfDay && !start.equals(end)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A half-day request must start and end on the same day");
        }
        if (!halfDay && period != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "halfDayPeriod is only allowed for a half day");
        }

        int days = businessDays.count(start, end, emp.locationCode());
        if (days == 0) {
            throw new ApiException(ErrorCode.NO_BUSINESS_DAYS, "No business days in the selected range");
        }
        BigDecimal total = halfDay ? HALF_DAY : BigDecimal.valueOf(days);

        // DEF-01 fix: AM and PM half days of the same date do not overlap each other.
        boolean overlap = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM hrms.leave_requests r
                    WHERE r.emp_id = :empId AND r.status IN ('PENDING', 'APPROVED')
                      AND r.start_date <= :end AND r.end_date >= :start
                      AND NOT (:halfDay AND r.half_day_flag AND r.start_date = r.end_date
                               AND r.half_day_period IS NOT NULL AND r.half_day_period <> :period))
                """).param("empId", empId).param("start", start).param("end", end).param("halfDay", halfDay)
                .param("period", period == null ? "" : period.name()).query(Boolean.class).single();
        if (overlap) {
            throw new ApiException(ErrorCode.LEAVE_OVERLAP, "Leave request overlaps with an existing request");
        }

        // Parity: the balance check reads the current calendar year (get_leave_balance default), see OQ-16.
        if (type.accrual()) {
            BigDecimal available = jdbc.sql("""
                    SELECT available FROM hrms.leave_balances
                    WHERE emp_id = :empId AND leave_type_id = :typeId AND calendar_year = :year
                    """).param("empId", empId).param("typeId", type.id()).param("year", today.getYear())
                    .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
            if (available.compareTo(total) < 0) {
                throw new ApiException(ErrorCode.INSUFFICIENT_LEAVE_BALANCE, "Insufficient leave balance. Available: "
                        + plain(available) + ", Requested: " + plain(total));
            }
        }

        // DEF-12 fix: REQUIRES_APPROVAL = false inserts APPROVED and moves the days straight to USED.
        boolean autoApproved = !type.requiresApproval();
        LeaveStatus status = autoApproved ? LeaveStatus.APPROVED : LeaveStatus.PENDING;
        String actor = caller.principal().actor();
        OffsetDateTime now = now();
        long requestId = jdbc.sql("""
                INSERT INTO hrms.leave_requests (emp_id, leave_type_id, start_date, end_date, total_days,
                    half_day_flag, half_day_period, status, reason, approver_emp_id, approval_date,
                    created_by, created_date)
                VALUES (:empId, :typeId, :start, :end, :total, :halfDay, :period, :status, :reason, :approver,
                    :approvalDate, :actor, :now)
                RETURNING request_id
                """).param("empId", empId).param("typeId", type.id()).param("start", start).param("end", end)
                .param("total", total).param("halfDay", halfDay).param("period", period == null ? null : period.name())
                .param("status", status.name()).param("reason", body.reason()).param("approver", emp.managerEmpId())
                .param("approvalDate", autoApproved ? now : null).param("actor", actor).param("now", now)
                .query(Long.class).single();

        adjustBalance(empId, type.id(), start.getYear(), autoApproved ? "used" : "pending", total, actor, now);

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("emp_id", empId);
        created.put("leave_type_id", type.id());
        created.put("start_date", start.toString());
        created.put("end_date", end.toString());
        created.put("total_days", total);
        created.put("half_day_period", period == null ? null : period.name());
        created.put("status", status.name());
        audit.write(TABLE, requestId, "INSERT", null, created, actor, caller.ipAddress(), caller.principal().sessionId());

        String range = start.format(US_DATE) + " to " + end.format(US_DATE);
        if (!autoApproved && emp.managerEmpId() != null) {
            notifications.queueEmail(emp.managerEmpId(), "Leave Request Pending Approval",
                    emp.firstName() + " " + emp.lastName() + " has requested " + plain(total) + " day(s) of "
                            + type.name() + " from " + range + ".", TABLE, requestId, actor);
        }
        if (autoApproved) {
            notifications.queueEmail(empId, "Leave Request Approved",
                    "Your leave request from " + range + " has been approved.", TABLE, requestId, actor);
        }
        return find(requestId);
    }

    // ---------------------------------------------------------------- decisions (PKG-L02)

    @Transactional
    public LeaveRequest approve(Caller caller, long requestId, String comments) {
        Locked r = lockForDecision(caller.principal(), requestId);
        String actor = caller.principal().actor();
        OffsetDateTime now = now();
        decide(r, LeaveStatus.APPROVED, caller, comments, now);
        moveBalance(r, "pending = pending - :days, used = used + :days", actor, now);
        notifications.queueEmail(r.empId(), "Leave Request Approved", "Your leave request from "
                + r.startDate().format(US_DATE) + " to " + r.endDate().format(US_DATE) + " has been approved.",
                TABLE, requestId, actor);
        return find(requestId);
    }

    @Transactional
    public LeaveRequest reject(Caller caller, long requestId, String comments) {
        Locked r = lockForDecision(caller.principal(), requestId);
        String actor = caller.principal().actor();
        OffsetDateTime now = now();
        decide(r, LeaveStatus.REJECTED, caller, comments, now);
        moveBalance(r, "pending = pending - :days", actor, now);
        notifications.queueEmail(r.empId(), "Leave Request Rejected",
                "Your leave request has been rejected. Reason: " + (comments == null ? "" : comments),
                TABLE, requestId, actor);
        return find(requestId);
    }

    /** FRM-051 gate (server-side): only PENDING or APPROVED requests can be cancelled, by their owner or LEAVE:ADMIN. */
    @Transactional
    public LeaveRequest cancel(Caller caller, long requestId, String reason) {
        HrmsPrincipal p = caller.principal();
        Locked r = lock(requestId);
        if (r.empId() != p.empId() && !p.has(Module.LEAVE, Action.ADMIN)) {
            boolean visible = (r.approverEmpId() != null && r.approverEmpId() == p.empId())
                    || p.has(Module.LEAVE, Action.APPROVE);
            if (!visible) {
                throw notFound();
            }
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the requester can cancel a leave request");
        }
        cancelLocked(r, reason, p.actor(), caller.ipAddress(), p.sessionId());
        return find(requestId);
    }

    /**
     * In-process entry point for P5 terminate (DEF-22): cancels the employee's PENDING requests through the
     * same transition, so the PENDING balance is released and each change is audited.
     */
    @Transactional
    public int cancelPendingRequestsOf(long empId, String reason, String actor) {
        List<Long> ids = jdbc.sql("SELECT request_id FROM hrms.leave_requests WHERE emp_id = :empId "
                + "AND status = 'PENDING' ORDER BY request_id FOR UPDATE")
                .param("empId", empId).query(Long.class).list();
        for (long id : ids) {
            cancelLocked(lock(id), reason, actor, null, null);
        }
        return ids.size();
    }

    private void cancelLocked(Locked r, String reason, String actor, String ip, Long sessionId) {
        if (!"PENDING".equals(r.status()) && !"APPROVED".equals(r.status())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST_STATUS,
                    "Only PENDING or APPROVED requests can be cancelled. Current status: " + r.status());
        }
        OffsetDateTime now = now();
        jdbc.sql("""
                UPDATE hrms.leave_requests SET status = 'CANCELLED', cancel_reason = :reason, cancelled_date = :now,
                    modified_by = :actor, modified_date = :now
                WHERE request_id = :id
                """).param("reason", reason).param("now", now).param("actor", actor).param("id", r.requestId())
                .update();
        moveBalance(r, "PENDING".equals(r.status()) ? "pending = pending - :days" : "used = used - :days", actor, now);
        auditStatus(r.requestId(), r.status(), "CANCELLED", actor, ip, sessionId);
    }

    private Locked lockForDecision(HrmsPrincipal p, long requestId) {
        Locked r = lock(requestId);
        if (r.empId() == p.empId()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You cannot approve or reject your own leave request");
        }
        boolean assigned = r.approverEmpId() != null && r.approverEmpId() == p.empId();
        if (!assigned && !p.has(Module.LEAVE, Action.APPROVE)) {
            throw notFound();
        }
        if (!"PENDING".equals(r.status())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST_STATUS,
                    "Leave request is not in PENDING status. Current status: " + r.status());
        }
        return r;
    }

    private void decide(Locked r, LeaveStatus to, Caller caller, String comments, OffsetDateTime now) {
        String actor = caller.principal().actor();
        jdbc.sql("""
                UPDATE hrms.leave_requests SET status = :status, approver_emp_id = :approver, approval_date = :now,
                    approval_comments = :comments, modified_by = :actor, modified_date = :now
                WHERE request_id = :id
                """).param("status", to.name()).param("approver", caller.principal().empId()).param("now", now)
                .param("comments", comments).param("actor", actor).param("id", r.requestId()).update();
        auditStatus(r.requestId(), r.status(), to.name(), actor, caller.ipAddress(), caller.principal().sessionId());
    }

    /** TRG-010 / DEF-15: one STATUS_CHANGE row per transition, in the same transaction (D-08). */
    private void auditStatus(long requestId, String from, String to, String actor, String ip, Long sessionId) {
        audit.write(TABLE, requestId, "STATUS_CHANGE", Map.of("status", from), Map.of("status", to), actor, ip,
                sessionId);
    }

    private void moveBalance(Locked r, String assignments, String actor, OffsetDateTime now) {
        jdbc.sql("UPDATE hrms.leave_balances SET " + assignments + ", modified_by = :actor, modified_date = :now "
                + "WHERE emp_id = :empId AND leave_type_id = :typeId AND calendar_year = :year")
                .param("days", r.totalDays()).param("actor", actor).param("now", now).param("empId", r.empId())
                .param("typeId", r.leaveTypeId()).param("year", r.startDate().getYear()).update();
    }

    private void adjustBalance(long empId, long typeId, int year, String column, BigDecimal days, String actor,
            OffsetDateTime now) {
        jdbc.sql("UPDATE hrms.leave_balances SET " + column + " = " + column + " + :days, modified_by = :actor, "
                + "modified_date = :now WHERE emp_id = :empId AND leave_type_id = :typeId AND calendar_year = :year")
                .param("days", days).param("actor", actor).param("now", now).param("empId", empId)
                .param("typeId", typeId).param("year", year).update();
    }

    private Locked lock(long requestId) {
        return jdbc.sql("""
                SELECT request_id, emp_id, leave_type_id, start_date, end_date, total_days, status, approver_emp_id
                FROM hrms.leave_requests WHERE request_id = :id FOR UPDATE
                """).param("id", requestId)
                .query((rs, i) -> new Locked(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getBigDecimal(6),
                        rs.getString(7), rs.getObject(8, Long.class)))
                .optional().orElseThrow(LeaveService::notFound);
    }

    private LeaveRequest find(long requestId) {
        return jdbc.sql(SELECT_REQUEST + " WHERE r.request_id = :id").param("id", requestId)
                .query(LeaveService::request).optional().orElseThrow(LeaveService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Leave request not found");
    }

    private static LeaveRequest request(ResultSet rs, int i) throws SQLException {
        return new LeaveRequest(rs.getLong("request_id"), rs.getLong("emp_id"), rs.getString("employee_name"),
                rs.getLong("leave_type_id"), rs.getString("leave_type_code"), rs.getString("leave_type_name"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getBigDecimal("total_days"), rs.getBoolean("half_day_flag"), period(rs.getString("half_day_period")),
                LeaveStatus.valueOf(rs.getString("status")), rs.getString("reason"),
                rs.getObject("approver_emp_id", Long.class), rs.getObject("approval_date", OffsetDateTime.class),
                rs.getString("approval_comments"), rs.getString("cancel_reason"),
                rs.getObject("cancelled_date", OffsetDateTime.class), rs.getObject("created_date", OffsetDateTime.class));
    }

    private static HalfDayPeriod period(String s) {
        return s == null ? null : HalfDayPeriod.valueOf(s);
    }

    static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
