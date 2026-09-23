package com.acme.hrms.leave;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.calendar.BusinessCalendar;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.leave.LeaveAccess.Employee;
import com.acme.hrms.leave.LeaveDtos.LeaveRequest;
import com.acme.hrms.leave.LeaveDtos.PageOfLeaveRequest;
import com.acme.hrms.leave.LeaveDtos.PendingLeaveApproval;
import com.acme.hrms.leave.LeaveDtos.TeamCalendarEntry;
import com.acme.hrms.leave.LeaveRequestRepository.NewRequest;
import com.acme.hrms.leave.LeaveTypeRepository.LeaveType;
import com.acme.hrms.notification.NotificationService;
import com.acme.hrms.validation.dto.leave.LeaveApproveRequest;
import com.acme.hrms.validation.dto.leave.LeaveCancelRequest;
import com.acme.hrms.validation.dto.leave.LeaveRejectRequest;
import com.acme.hrms.validation.dto.leave.LeaveRequestCreateRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code PKG_LEAVE.submit/cancel/approve/reject_leave_request} on PostgreSQL with the error
 * contract of contracts/p2-leave/error-codes.md. Audit and notification rows are written explicitly
 * here (no triggers): a status transition writes the package's {@code UPDATE} row followed by the
 * {@code STATUS_CHANGE} row of {@code TRG_LEAVE_REQUEST_AUDIT}; balance mutations go through {@link
 * LeaveBalanceRepository} and are no-ops without a balance row (QUIRK-02).
 */
@Service
public class LeaveRequestService {

  static final String TABLE = "LEAVE_REQUESTS";
  static final int NOTIFICATION_PRIORITY = 5;
  static final String DEFAULT_CANCEL_REASON = "Cancelled by employee";
  static final String PENDING_SUBJECT = "Leave Request Pending Approval";
  static final String APPROVED_SUBJECT = "Leave Request Approved";
  static final String REJECTED_SUBJECT = "Leave Request Rejected";
  static final BigDecimal HALF_DAY = new BigDecimal("0.5");
  private static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM/dd/yyyy");
  private static final Set<String> STATUSES =
      Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED", "TAKEN");

  private final LeaveRequestRepository requests;
  private final LeaveBalanceRepository balances;
  private final LeaveTypeRepository leaveTypes;
  private final LeaveAccess access;
  private final BusinessCalendar calendar;
  private final AuditService audit;
  private final NotificationService notifications;
  private final Clock clock;

  public LeaveRequestService(
      LeaveRequestRepository requests,
      LeaveBalanceRepository balances,
      LeaveTypeRepository leaveTypes,
      LeaveAccess access,
      BusinessCalendar calendar,
      AuditService audit,
      NotificationService notifications,
      Clock clock) {
    this.requests = requests;
    this.balances = balances;
    this.leaveTypes = leaveTypes;
    this.access = access;
    this.calendar = calendar;
    this.audit = audit;
    this.notifications = notifications;
    this.clock = clock;
  }

  // ---------------------------------------------------------------- reads

  @Transactional(readOnly = true)
  public List<LeaveRequest> listMine(
      CallerIdentity caller, @Nullable String status, @Nullable Integer year) {
    List<String> statuses = parseStatuses(status);
    validateYear(year);
    access.requireActive(caller.empId());
    return requests.listForEmployee(caller.empId(), statuses, year, 0, Integer.MAX_VALUE);
  }

  @Transactional(readOnly = true)
  public PageOfLeaveRequest listForEmployee(
      long empId, @Nullable String status, @Nullable Integer year, int page, int size) {
    List<String> statuses = parseStatuses(status);
    validateYear(year);
    if (empId < 1) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "empId must be >= 1", "empId");
    }
    if (page < 0) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "page must be >= 0", "page");
    }
    if (size < 1 || size > 100) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "size must be 1..100", "size");
    }
    access.requireExists(empId);
    long total = requests.countForEmployee(empId, statuses, year);
    List<LeaveRequest> content = requests.listForEmployee(empId, statuses, year, page * size, size);
    int totalPages = (int) ((total + size - 1) / size);
    return new PageOfLeaveRequest(content, page, size, total, totalPages);
  }

  @Transactional(readOnly = true)
  public LeaveRequest get(long requestId, CallerIdentity caller) {
    LeaveRequest r = requireRequest(requestId);
    access.requireReadable(r, caller);
    return r;
  }

  @Transactional(readOnly = true)
  public List<PendingLeaveApproval> pendingApprovals(CallerIdentity caller) {
    access.requireActive(caller.empId());
    return requests.pendingFor(caller.empId());
  }

  @Transactional(readOnly = true)
  public List<TeamCalendarEntry> teamCalendar(CallerIdentity caller, LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      throw new HrmsException(ErrorCode.LEAVE_DATE_ORDER, "to");
    }
    if (ChronoUnit.DAYS.between(from, to) > 366) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "Range may not exceed 366 days", "to");
    }
    access.requireActive(caller.empId());
    return requests.teamCalendar(caller.empId(), from, to);
  }

  static List<String> parseStatuses(@Nullable String status) {
    if (status == null || status.isBlank()) {
      return List.of();
    }
    List<String> parts = Arrays.stream(status.split(",")).map(String::trim).toList();
    for (String p : parts) {
      if (!STATUSES.contains(p)) {
        throw new HrmsException(ErrorCode.VALIDATION_FAILED, "Unknown status: " + p, "status");
      }
    }
    return parts;
  }

  private static void validateYear(@Nullable Integer year) {
    if (year != null && (year < 2000 || year > 2099)) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "year must be 2000..2099", "year");
    }
  }

  private LeaveRequest requireRequest(long requestId) {
    return requests
        .find(requestId)
        .orElseThrow(() -> new HrmsException(ErrorCode.LEAVE_REQUEST_NOT_FOUND));
  }

  // ---------------------------------------------------------------- submit

  @Transactional
  public LeaveRequest submit(LeaveRequestCreateRequest body, CallerIdentity caller) {
    access.validateWithDateOrder(body, "endDate");
    Employee emp = access.requireActive(caller.empId());
    LocalDate today = LocalDate.now(clock);

    // 2. active leave type
    LeaveType type =
        leaveTypes
            .findActive(body.getLeaveTypeId())
            .orElseThrow(
                () ->
                    new HrmsException(
                        ErrorCode.LEAVE_TYPE_INVALID,
                        "Invalid leave type: " + body.getLeaveTypeId(),
                        "leaveTypeId"));
    // 3. tenure
    long tenureDays = ChronoUnit.DAYS.between(emp.hireDate(), today);
    if (tenureDays < type.minTenureDays()) {
      throw new HrmsException(
          ErrorCode.LEAVE_TYPE_INVALID,
          "Minimum tenure of "
              + type.minTenureDays()
              + " days not met for leave type: "
              + type.leaveTypeName(),
          "leaveTypeId");
    }
    LocalDate start = body.getStartDate();
    LocalDate end = body.getEndDate();
    // 4. date order (defensive)
    if (start.isAfter(end)) {
      throw new HrmsException(ErrorCode.LEAVE_DATE_ORDER, "endDate");
    }
    // 5. more than 5 calendar days in the past
    if (ChronoUnit.DAYS.between(start, today) > LeaveRequestCreateRequest.MAX_DAYS_IN_PAST) {
      throw new HrmsException(ErrorCode.LEAVE_TOO_FAR_IN_PAST, "startDate");
    }
    // 6. business days (BUG-05 observed holidays); half day must fall on a business day
    BigDecimal totalDays;
    if (body.isHalfDay()) {
      if (!calendar.isBusinessDay(start, emp.locationCode())) {
        throw new HrmsException(ErrorCode.LEAVE_NO_BUSINESS_DAYS, "startDate");
      }
      totalDays = HALF_DAY;
    } else {
      int days = calendar.countBusinessDays(start, end, emp.locationCode());
      if (days <= 0) {
        throw new HrmsException(ErrorCode.LEAVE_NO_BUSINESS_DAYS, "startDate");
      }
      totalDays = BigDecimal.valueOf(days);
    }
    // 7. overlap (BUG-06 exception)
    if (requests.overlaps(emp.empId(), start, end, body.isHalfDay(), body.getHalfDayPeriod())) {
      throw new HrmsException(ErrorCode.LEAVE_OVERLAP, "startDate");
    }
    // 8. balance of the *current* year (QUIRK-01)
    if (type.accrual()) {
      BigDecimal available = balances.available(emp.empId(), type.leaveTypeId(), today.getYear());
      if (available.compareTo(totalDays) < 0) {
        throw new HrmsException(
            ErrorCode.LEAVE_INSUFFICIENT_BALANCE,
            "Insufficient leave balance. Available: "
                + OracleNumber.render(available)
                + ", Requested: "
                + OracleNumber.render(totalDays),
            "leaveTypeId");
      }
    }

    boolean autoApproved = !type.requiresApproval();
    String status = autoApproved ? "APPROVED" : "PENDING";
    long id =
        requests.insert(
            new NewRequest(
                emp.empId(),
                type.leaveTypeId(),
                start,
                end,
                totalDays,
                body.isHalfDay(),
                body.isHalfDay() ? body.getHalfDayPeriod() : null,
                status,
                body.getReason(),
                emp.managerEmpId(),
                autoApproved ? LocalDateTime.now(clock) : null,
                caller.userId()));
    int year = start.getYear();
    if (autoApproved) {
      balances.addUsed(emp.empId(), type.leaveTypeId(), year, totalDays, caller.userId());
    } else {
      balances.addPending(emp.empId(), type.leaveTypeId(), year, totalDays, caller.userId());
    }
    LeaveRequest created = requests.get(id);
    audit.log(
        TABLE,
        id,
        AuditService.Action.INSERT,
        null,
        statusJson(created),
        caller.userId(),
        null,
        null);
    if (autoApproved) {
      notifications.enqueue(
          emp.empId(),
          null,
          NotificationService.Type.EMAIL,
          APPROVED_SUBJECT,
          approvedBody(created),
          NOTIFICATION_PRIORITY,
          TABLE,
          id,
          caller.userId());
    } else if (emp.managerEmpId() != null) {
      notifications.enqueue(
          emp.managerEmpId(),
          null,
          NotificationService.Type.EMAIL,
          PENDING_SUBJECT,
          emp.firstName()
              + " "
              + emp.lastName()
              + " has requested "
              + OracleNumber.render(totalDays)
              + " day(s) of "
              + type.leaveTypeName()
              + " from "
              + US.format(start)
              + " to "
              + US.format(end)
              + ".",
          NOTIFICATION_PRIORITY,
          TABLE,
          id,
          caller.userId());
    }
    return created;
  }

  // ---------------------------------------------------------------- cancel

  @Transactional
  public LeaveRequest cancel(long requestId, @Nullable LeaveCancelRequest body, CallerIdentity c) {
    LeaveRequest before = requireRequest(requestId);
    access.requireOwner(before, c);
    String reason = body == null ? null : access.validate(body).getReason();
    if (!"PENDING".equals(before.status()) && !"APPROVED".equals(before.status())) {
      throw new HrmsException(
          ErrorCode.LEAVE_STATUS_INVALID,
          "Cannot cancel request in status: " + before.status(),
          null);
    }
    requests.cancel(requestId, reason == null ? DEFAULT_CANCEL_REASON : reason, c.userId());
    int year = before.startDate().getYear();
    if ("PENDING".equals(before.status())) {
      balances.addPending(
          before.empId(), before.leaveTypeId(), year, before.totalDays().negate(), c.userId());
    } else {
      balances.addUsed(
          before.empId(), before.leaveTypeId(), year, before.totalDays().negate(), c.userId());
    }
    LeaveRequest after = requests.get(requestId);
    auditStatus(before, after, c);
    return after;
  }

  // ---------------------------------------------------------------- approve / reject

  @Transactional
  public LeaveRequest approve(
      long requestId, @Nullable LeaveApproveRequest body, CallerIdentity c) {
    LeaveRequest before = requireRequest(requestId);
    access.requireApprover(before, c);
    String comments = body == null ? null : access.validate(body).getComments();
    requirePending(before, "approve");
    requests.decide(requestId, "APPROVED", c.empId(), comments, c.userId());
    balances.pendingToUsed(
        before.empId(),
        before.leaveTypeId(),
        before.startDate().getYear(),
        before.totalDays(),
        c.userId());
    LeaveRequest after = requests.get(requestId);
    auditStatus(before, after, c);
    notifications.enqueue(
        after.empId(),
        null,
        NotificationService.Type.EMAIL,
        APPROVED_SUBJECT,
        approvedBody(after),
        NOTIFICATION_PRIORITY,
        TABLE,
        requestId,
        c.userId());
    return after;
  }

  @Transactional
  public LeaveRequest reject(long requestId, @Nullable LeaveRejectRequest body, CallerIdentity c) {
    LeaveRequest before = requireRequest(requestId);
    access.requireApprover(before, c);
    if (body == null) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "Request body is required", "comments");
    }
    String comments = access.validate(body).getComments();
    requirePending(before, "reject");
    requests.decide(requestId, "REJECTED", c.empId(), comments, c.userId());
    balances.addPending(
        before.empId(),
        before.leaveTypeId(),
        before.startDate().getYear(),
        before.totalDays().negate(),
        c.userId());
    LeaveRequest after = requests.get(requestId);
    auditStatus(before, after, c);
    notifications.enqueue(
        after.empId(),
        null,
        NotificationService.Type.EMAIL,
        REJECTED_SUBJECT,
        "Your leave request has been rejected. Reason: " + comments,
        NOTIFICATION_PRIORITY,
        TABLE,
        requestId,
        c.userId());
    return after;
  }

  private static void requirePending(LeaveRequest r, String verb) {
    if (!"PENDING".equals(r.status())) {
      throw new HrmsException(
          ErrorCode.LEAVE_STATUS_INVALID,
          "Cannot " + verb + " request in status: " + r.status(),
          null);
    }
  }

  private static String approvedBody(LeaveRequest r) {
    return "Your leave request from "
        + US.format(r.startDate())
        + " to "
        + US.format(r.endDate())
        + " has been approved.";
  }

  private void auditStatus(LeaveRequest before, LeaveRequest after, CallerIdentity c) {
    audit.log(
        TABLE, after.requestId(), AuditService.Action.UPDATE, null, null, c.userId(), null, null);
    audit.log(
        TABLE,
        after.requestId(),
        AuditService.Action.STATUS_CHANGE,
        statusJson(before),
        statusJson(after),
        c.userId(),
        null,
        null);
  }

  static String statusJson(LeaveRequest r) {
    return "{\"status\":\"" + r.status() + "\"}";
  }
}
