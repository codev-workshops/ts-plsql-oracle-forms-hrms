package com.hrms.leave;

import com.hrms.auth.HrmsPrincipal;
import com.hrms.leave.LeaveModels.AccrualRun;
import com.hrms.leave.LeaveModels.AccrualRunRequest;
import com.hrms.leave.LeaveModels.CancelLeaveRequest;
import com.hrms.leave.LeaveModels.CarryoverExpiryRequest;
import com.hrms.leave.LeaveModels.CarryoverRun;
import com.hrms.leave.LeaveModels.CarryoverRunRequest;
import com.hrms.leave.LeaveModels.LeaveBalance;
import com.hrms.leave.LeaveModels.LeaveDecision;
import com.hrms.leave.LeaveModels.LeaveRequest;
import com.hrms.leave.LeaveModels.LeaveStatus;
import com.hrms.leave.LeaveModels.LeaveType;
import com.hrms.leave.LeaveModels.SubmitLeaveRequest;
import com.hrms.leave.LeaveModels.TeamCalendarEntry;
import com.hrms.leave.LeaveService.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leave pilot API (contract v1). Commands only — no generic CRUD on requests or balances (FRM-053).
 * Lists are scoped to the caller (FRM-050, OQ-09); decisions are authorized per row in {@link LeaveService}.
 */
@RestController
@RequestMapping("/api/v1/leave")
@PreAuthorize("hasAuthority('LEAVE:VIEW')")
public class LeaveController {

    private final LeaveService leave;
    private final LeaveAccrualService accrual;
    private final LeaveCarryoverService carryover;
    private final Clock clock;

    public LeaveController(LeaveService leave, LeaveAccrualService accrual, LeaveCarryoverService carryover,
            Clock clock) {
        this.leave = leave;
        this.accrual = accrual;
        this.carryover = carryover;
        this.clock = clock;
    }

    @GetMapping("/types")
    List<LeaveType> types() {
        return leave.activeTypes();
    }

    @GetMapping("/balances")
    List<LeaveBalance> balances(@AuthenticationPrincipal HrmsPrincipal p,
            @RequestParam(required = false) @Min(1900) @Max(9999) Integer year) {
        return leave.balances(p.empId(), year != null ? year : LocalDate.now(clock).getYear());
    }

    @GetMapping("/requests")
    List<LeaveRequest> myRequests(@AuthenticationPrincipal HrmsPrincipal p,
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) @Min(1900) @Max(9999) Integer year) {
        return leave.requestsOf(p.empId(), status, year);
    }

    @PostMapping("/requests")
    @PreAuthorize("hasAuthority('LEAVE:CREATE')")
    ResponseEntity<LeaveRequest> submit(@AuthenticationPrincipal HrmsPrincipal p,
            @Valid @RequestBody SubmitLeaveRequest body, HttpServletRequest http) {
        LeaveRequest created = leave.submit(new Caller(p, http.getRemoteAddr()), body);
        return ResponseEntity.created(URI.create("/api/v1/leave/requests/" + created.requestId())).body(created);
    }

    @GetMapping("/requests/{requestId}")
    LeaveRequest get(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long requestId) {
        return leave.get(requestId, p);
    }

    @PostMapping("/requests/{requestId}/approve")
    LeaveRequest approve(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long requestId,
            @Valid @RequestBody(required = false) LeaveDecision body, HttpServletRequest http) {
        return leave.approve(new Caller(p, http.getRemoteAddr()), requestId, body == null ? null : body.comments());
    }

    @PostMapping("/requests/{requestId}/reject")
    LeaveRequest reject(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long requestId,
            @Valid @RequestBody(required = false) LeaveDecision body, HttpServletRequest http) {
        return leave.reject(new Caller(p, http.getRemoteAddr()), requestId, body == null ? null : body.comments());
    }

    @PostMapping("/requests/{requestId}/cancel")
    LeaveRequest cancel(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long requestId,
            @Valid @RequestBody(required = false) CancelLeaveRequest body, HttpServletRequest http) {
        return leave.cancel(new Caller(p, http.getRemoteAddr()), requestId, body == null ? null : body.reason());
    }

    @GetMapping("/approvals")
    List<LeaveRequest> approvals(@AuthenticationPrincipal HrmsPrincipal p) {
        return leave.pendingApprovals(p.empId());
    }

    @GetMapping("/team-calendar")
    List<TeamCalendarEntry> teamCalendar(@AuthenticationPrincipal HrmsPrincipal p,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return leave.teamCalendar(p.empId(), from, to);
    }

    @PostMapping("/accrual-runs")
    @PreAuthorize("hasAuthority('LEAVE:ADMIN')")
    ResponseEntity<AccrualRun> runAccrual(@AuthenticationPrincipal HrmsPrincipal p,
            @Valid @RequestBody AccrualRunRequest body) {
        LeaveAccrualService.Outcome o = accrual.run(body.accrualDate(), p.actor());
        return ResponseEntity.status(o.created() ? HttpStatus.CREATED : HttpStatus.OK).body(o.run());
    }

    @PostMapping("/carryover-runs")
    @PreAuthorize("hasAuthority('LEAVE:ADMIN')")
    ResponseEntity<CarryoverRun> runCarryover(@AuthenticationPrincipal HrmsPrincipal p,
            @Valid @RequestBody CarryoverRunRequest body) {
        LeaveCarryoverService.Outcome o = carryover.carryOver(body.year(), p.actor());
        return ResponseEntity.status(o.created() ? HttpStatus.CREATED : HttpStatus.OK).body(o.run());
    }

    @PostMapping("/carryover-expiry-runs")
    @PreAuthorize("hasAuthority('LEAVE:ADMIN')")
    ResponseEntity<CarryoverRun> runCarryoverExpiry(@AuthenticationPrincipal HrmsPrincipal p,
            @Valid @RequestBody CarryoverExpiryRequest body) {
        LeaveCarryoverService.Outcome o = carryover.expire(body.asOfDate(), p.actor());
        return ResponseEntity.status(o.created() ? HttpStatus.CREATED : HttpStatus.OK).body(o.run());
    }
}
