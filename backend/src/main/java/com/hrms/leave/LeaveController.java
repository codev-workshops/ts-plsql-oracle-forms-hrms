package com.hrms.leave;

import com.hrms.leave.LeaveModels.AccrualRunRequest;
import com.hrms.leave.LeaveModels.BatchResult;
import com.hrms.leave.LeaveModels.BusinessDays;
import com.hrms.leave.LeaveModels.CancelRequest;
import com.hrms.leave.LeaveModels.CarryoverRequest;
import com.hrms.leave.LeaveModels.DecisionRequest;
import com.hrms.leave.LeaveModels.InitializeBalancesRequest;
import com.hrms.leave.LeaveModels.LeaveBalance;
import com.hrms.leave.LeaveModels.LeaveRequest;
import com.hrms.leave.LeaveModels.LeaveType;
import com.hrms.leave.LeaveModels.RejectRequest;
import com.hrms.leave.LeaveModels.SubmitLeaveRequest;
import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leave")
public class LeaveController {

    private final LeaveService leave;
    private final LeaveBatchService batch;
    private final PermissionService permissions;
    private final Clock clock;

    public LeaveController(LeaveService leave, LeaveBatchService batch, PermissionService permissions, Clock clock) {
        this.leave = leave;
        this.batch = batch;
        this.permissions = permissions;
        this.clock = clock;
    }

    @GetMapping("/types")
    public List<LeaveType> types(CurrentUser user) {
        return leave.activeTypes();
    }

    @GetMapping("/balances")
    public List<LeaveBalance> balances(CurrentUser user, @RequestParam(required = false) Integer year) {
        return leave.balances(user, year);
    }

    @GetMapping("/business-days")
    public BusinessDays businessDays(CurrentUser user,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return new BusinessDays(startDate, endDate, leave.businessDays(user, startDate, endDate));
    }

    @GetMapping("/requests")
    public List<LeaveRequest> myRequests(CurrentUser user, @RequestParam(required = false) String status) {
        return leave.myRequests(user, status);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequest submit(CurrentUser user, @Valid @RequestBody SubmitLeaveRequest request) {
        permissions.require(user, Module.LEAVE, Action.CREATE);
        return leave.submit(user, request);
    }

    @GetMapping("/requests/{requestId}")
    public LeaveRequest get(CurrentUser user, @PathVariable long requestId) {
        return leave.get(user, requestId);
    }

    @PostMapping("/requests/{requestId}/cancel")
    public LeaveRequest cancel(CurrentUser user, @PathVariable long requestId,
                               @Valid @RequestBody CancelRequest request) {
        return leave.cancel(user, requestId, request.reason());
    }

    @PostMapping("/requests/{requestId}/approve")
    public LeaveRequest approve(CurrentUser user, @PathVariable long requestId,
                                @Valid @RequestBody(required = false) DecisionRequest request) {
        return leave.approve(user, requestId, request == null ? null : request.comments());
    }

    @PostMapping("/requests/{requestId}/reject")
    public LeaveRequest reject(CurrentUser user, @PathVariable long requestId,
                               @Valid @RequestBody RejectRequest request) {
        return leave.reject(user, requestId, request.comments());
    }

    @GetMapping("/approvals")
    public List<LeaveRequest> approvals(CurrentUser user) {
        return leave.pendingApprovals(user);
    }

    @GetMapping("/team-calendar")
    public List<LeaveRequest> teamCalendar(CurrentUser user,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return leave.teamCalendar(user, startDate, endDate);
    }

    @PostMapping("/admin/balances/initialize")
    public BatchResult initialize(CurrentUser user, @Valid @RequestBody InitializeBalancesRequest request) {
        permissions.require(user, Module.LEAVE, Action.EDIT);
        return new BatchResult("INITIALIZE_BALANCES",
                batch.initializeBalances(request.empId(), request.year(), user.username()));
    }

    @PostMapping("/admin/accruals")
    public BatchResult accrue(CurrentUser user, @RequestBody(required = false) AccrualRunRequest request) {
        permissions.require(user, Module.LEAVE, Action.EDIT);
        LocalDate date = request == null || request.accrualDate() == null ? LocalDate.now(clock) : request.accrualDate();
        return new BatchResult("MONTHLY_ACCRUAL", batch.runMonthlyAccrual(date, user.username()));
    }

    @PostMapping("/admin/carryover")
    public BatchResult carryover(CurrentUser user, @Valid @RequestBody CarryoverRequest request) {
        permissions.require(user, Module.LEAVE, Action.EDIT);
        return new BatchResult("CARRYOVER", batch.processCarryover(request.year(), user.username()));
    }

    @PostMapping("/admin/carryover/expire")
    public BatchResult expire(CurrentUser user) {
        permissions.require(user, Module.LEAVE, Action.EDIT);
        return new BatchResult("EXPIRE_CARRYOVER", batch.expireCarryover(LocalDate.now(clock), user.username()));
    }
}
