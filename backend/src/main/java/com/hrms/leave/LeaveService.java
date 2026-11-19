package com.hrms.leave;

import com.hrms.leave.LeaveModels.LeaveBalance;
import com.hrms.leave.LeaveModels.LeaveRequest;
import com.hrms.leave.LeaveModels.LeaveType;
import com.hrms.leave.LeaveModels.SubmitLeaveRequest;
import com.hrms.leave.LeaveRepository.TypeRow;
import com.hrms.platform.audit.AuditService;
import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.calendar.BusinessDayCalendar;
import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.employee.EmployeeSummary;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.notification.NotificationService;
import com.hrms.platform.param.ParameterService;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave workflow ported from PKG_LEAVE (inventory §4.6) with the §9 fixes: balance rows are
 * initialized on demand, the start-date year is used consistently, auto-approved types settle
 * straight to USED, and only the requester's manager (or a grade >= 8 user, never the
 * requester) may approve or reject.
 */
@Service
public class LeaveService {

    static final BigDecimal HALF_DAY = new BigDecimal("0.5");

    private final LeaveRepository repo;
    private final EmployeeLookup employees;
    private final BusinessDayCalendar calendar;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ParameterService params;
    private final PermissionService permissions;
    private final Clock clock;

    public LeaveService(LeaveRepository repo, EmployeeLookup employees, BusinessDayCalendar calendar,
                        NotificationService notifications, AuditService audit, ParameterService params,
                        PermissionService permissions, Clock clock) {
        this.repo = repo;
        this.employees = employees;
        this.calendar = calendar;
        this.notifications = notifications;
        this.audit = audit;
        this.params = params;
        this.permissions = permissions;
        this.clock = clock;
    }

    public List<LeaveType> activeTypes() {
        return repo.allTypes().stream().filter(TypeRow::active).map(TypeRow::type).toList();
    }

    public List<LeaveBalance> balances(CurrentUser user, Integer year) {
        int y = year != null ? year : LocalDate.now(clock).getYear();
        return repo.balances(user.empId(), y);
    }

    public List<LeaveRequest> myRequests(CurrentUser user, String status) {
        return repo.requestsForEmployee(user.empId(), status == null || status.isBlank() ? null : status.toUpperCase());
    }

    public LeaveRequest get(CurrentUser user, long requestId) {
        LeaveRequest r = load(requestId);
        boolean allowed = r.empId() == user.empId()
                || (r.approverEmpId() != null && r.approverEmpId() == user.empId())
                || employees.isManagerOf(user.empId(), r.empId())
                || permissions.hasPermission(user, Module.LEAVE, Action.APPROVE);
        if (!allowed) {
            throw new ApiException(ErrorCode.LEAVE_NOT_OWNER, "Not allowed to view this leave request");
        }
        return r;
    }

    public int businessDays(CurrentUser user, LocalDate start, LocalDate end) {
        String location = employees.findById(user.empId()).map(EmployeeSummary::locationCode).orElse(null);
        return calendar.businessDays(start, end, location);
    }

    @Transactional
    public LeaveRequest submit(CurrentUser user, SubmitLeaveRequest req) {
        EmployeeSummary emp = employees.findById(user.empId())
                .filter(EmployeeSummary::isActiveEmployee)
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Employee not found or not active: " + user.empId()));
        TypeRow typeRow = repo.findType(req.leaveTypeId()).filter(TypeRow::active)
                .orElseThrow(() -> new ApiException(ErrorCode.LEAVE_INVALID_TYPE,
                        "Invalid leave type: " + req.leaveTypeId()));
        LeaveType type = typeRow.type();
        LocalDate today = LocalDate.now(clock);
        if (emp.hireDate() != null && ChronoUnit.DAYS.between(emp.hireDate(), today) < type.minTenureDays()) {
            throw new ApiException(ErrorCode.LEAVE_INVALID_TYPE,
                    "Minimum tenure of " + type.minTenureDays() + " days required for " + type.name());
        }
        if (req.startDate().isAfter(req.endDate())) {
            throw new ApiException(ErrorCode.LEAVE_START_AFTER_END, "Start date must be before or equal to end date");
        }
        int maxBackdate = params.getInt("LEAVE", "MAX_BACKDATE_DAYS", 5);
        if (ChronoUnit.DAYS.between(req.startDate(), today) > maxBackdate) {
            throw new ApiException(ErrorCode.LEAVE_BACKDATED,
                    "Cannot submit leave requests more than " + maxBackdate + " days in the past");
        }
        boolean halfDay = Boolean.TRUE.equals(req.halfDay());
        if (halfDay && !req.startDate().equals(req.endDate())) {
            throw new ApiException(ErrorCode.VALIDATION, "A half-day request must start and end on the same day");
        }
        int businessDays = calendar.businessDays(req.startDate(), req.endDate(), emp.locationCode());
        if (businessDays == 0) {
            throw new ApiException(ErrorCode.LEAVE_NO_BUSINESS_DAYS, "No business days in the selected range");
        }
        BigDecimal days = halfDay ? HALF_DAY : BigDecimal.valueOf(businessDays);
        if (repo.overlaps(emp.empId(), req.startDate(), req.endDate(), null)) {
            throw new ApiException(ErrorCode.LEAVE_OVERLAP, "Leave request overlaps with an existing request");
        }
        int year = req.startDate().getYear();
        repo.ensureBalance(emp.empId(), type.leaveTypeId(), year, user.username());
        if (type.accrual()) {
            BigDecimal available = repo.balance(emp.empId(), type.leaveTypeId(), year)
                    .map(LeaveBalance::available).orElse(BigDecimal.ZERO);
            if (available.compareTo(days) < 0) {
                throw new ApiException(ErrorCode.LEAVE_INSUFFICIENT_BALANCE,
                        "Insufficient leave balance. Available: " + available.stripTrailingZeros().toPlainString()
                                + ", Requested: " + days.stripTrailingZeros().toPlainString());
            }
        }
        boolean needsApproval = type.requiresApproval();
        LocalDateTime now = LocalDateTime.now(clock);
        long requestId = repo.insertRequest(emp.empId(), type.leaveTypeId(), req.startDate(), req.endDate(), days,
                halfDay, halfDay ? req.halfDayPeriod() : null, needsApproval ? "PENDING" : "APPROVED", req.reason(),
                emp.managerEmpId(), needsApproval ? null : now, needsApproval ? null : "Auto-approved",
                user.username());
        if (needsApproval) {
            repo.adjustBalance(emp.empId(), type.leaveTypeId(), year, days, BigDecimal.ZERO, user.username());
            if (emp.managerEmpId() != null) {
                notifications.queue(emp.managerEmpId(), "Leave request pending approval",
                        emp.fullName() + " requested " + days.stripTrailingZeros().toPlainString() + " day(s) of "
                                + type.name() + " from " + req.startDate() + " to " + req.endDate() + ".",
                        "LEAVE_REQUESTS", requestId, user.username());
            }
        } else {
            repo.adjustBalance(emp.empId(), type.leaveTypeId(), year, BigDecimal.ZERO, days, user.username());
        }
        LeaveRequest created = load(requestId);
        audit.record("LEAVE_REQUESTS", requestId, AuditService.Action.INSERT, null, created, user.username());
        return created;
    }

    @Transactional
    public LeaveRequest approve(CurrentUser user, long requestId, String comments) {
        LeaveRequest r = lockForDecision(user, requestId);
        repo.updateDecision(requestId, "APPROVED", user.empId(), LocalDateTime.now(clock), comments, user.username());
        repo.ensureBalance(r.empId(), r.leaveTypeId(), r.startDate().getYear(), user.username());
        repo.adjustBalance(r.empId(), r.leaveTypeId(), r.startDate().getYear(), r.totalDays().negate(),
                r.totalDays(), user.username());
        notifications.queue(r.empId(), "Leave request approved",
                "Your " + r.leaveTypeName() + " request from " + r.startDate() + " to " + r.endDate()
                        + " was approved by " + user.fullName() + ".", "LEAVE_REQUESTS", requestId, user.username());
        LeaveRequest updated = load(requestId);
        audit.record("LEAVE_REQUESTS", requestId, AuditService.Action.UPDATE, Map.of("status", r.status()),
                Map.of("status", updated.status(), "approverEmpId", user.empId()), user.username());
        return updated;
    }

    @Transactional
    public LeaveRequest reject(CurrentUser user, long requestId, String comments) {
        LeaveRequest r = lockForDecision(user, requestId);
        repo.updateDecision(requestId, "REJECTED", user.empId(), LocalDateTime.now(clock), comments, user.username());
        repo.ensureBalance(r.empId(), r.leaveTypeId(), r.startDate().getYear(), user.username());
        repo.adjustBalance(r.empId(), r.leaveTypeId(), r.startDate().getYear(), r.totalDays().negate(),
                BigDecimal.ZERO, user.username());
        notifications.queue(r.empId(), "Leave request rejected",
                "Your " + r.leaveTypeName() + " request from " + r.startDate() + " to " + r.endDate()
                        + " was rejected. Reason: " + comments, "LEAVE_REQUESTS", requestId, user.username());
        LeaveRequest updated = load(requestId);
        audit.record("LEAVE_REQUESTS", requestId, AuditService.Action.UPDATE, Map.of("status", r.status()),
                Map.of("status", updated.status(), "approverEmpId", user.empId()), user.username());
        return updated;
    }

    @Transactional
    public LeaveRequest cancel(CurrentUser user, long requestId, String reason) {
        LeaveRequest r = repo.lockRequest(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Leave request not found: " + requestId));
        if (r.empId() != user.empId() && !permissions.hasPermission(user, Module.LEAVE, Action.EDIT)) {
            throw new ApiException(ErrorCode.LEAVE_NOT_OWNER, "Only the requester can cancel this leave request");
        }
        if (!"PENDING".equals(r.status()) && !"APPROVED".equals(r.status())) {
            throw new ApiException(ErrorCode.LEAVE_WRONG_STATUS,
                    "Only pending or approved requests can be cancelled. Current status: " + r.status());
        }
        repo.updateCancelled(requestId, reason, LocalDateTime.now(clock), user.username());
        int year = r.startDate().getYear();
        repo.ensureBalance(r.empId(), r.leaveTypeId(), year, user.username());
        if ("PENDING".equals(r.status())) {
            repo.adjustBalance(r.empId(), r.leaveTypeId(), year, r.totalDays().negate(), BigDecimal.ZERO,
                    user.username());
        } else {
            repo.adjustBalance(r.empId(), r.leaveTypeId(), year, BigDecimal.ZERO, r.totalDays().negate(),
                    user.username());
        }
        LeaveRequest updated = load(requestId);
        audit.record("LEAVE_REQUESTS", requestId, AuditService.Action.UPDATE, Map.of("status", r.status()),
                Map.of("status", updated.status(), "cancelReason", reason), user.username());
        return updated;
    }

    public List<LeaveRequest> pendingApprovals(CurrentUser user) {
        return repo.pendingForApprover(user.empId());
    }

    public List<LeaveRequest> teamCalendar(CurrentUser user, LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new ApiException(ErrorCode.LEAVE_START_AFTER_END, "Start date must be before or equal to end date");
        }
        return repo.teamCalendar(user.empId(), start, end);
    }

    private LeaveRequest lockForDecision(CurrentUser user, long requestId) {
        LeaveRequest r = repo.lockRequest(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Leave request not found: " + requestId));
        if (r.empId() == user.empId()) {
            throw new ApiException(ErrorCode.LEAVE_NOT_APPROVER, "You cannot approve or reject your own request");
        }
        boolean isApprover = r.approverEmpId() != null && r.approverEmpId() == user.empId();
        if (!isApprover && !permissions.hasPermission(user, Module.LEAVE, Action.APPROVE)) {
            throw new ApiException(ErrorCode.LEAVE_NOT_APPROVER,
                    "Only the requester's manager can approve or reject this request");
        }
        if (!"PENDING".equals(r.status())) {
            throw new ApiException(ErrorCode.LEAVE_WRONG_STATUS,
                    "Only pending requests can be approved or rejected. Current status: " + r.status());
        }
        return r;
    }

    private LeaveRequest load(long requestId) {
        return repo.findRequest(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Leave request not found: " + requestId));
    }
}
