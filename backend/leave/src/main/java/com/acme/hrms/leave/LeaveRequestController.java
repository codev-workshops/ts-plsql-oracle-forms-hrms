package com.acme.hrms.leave;

import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.leave.LeaveDtos.LeaveRequest;
import com.acme.hrms.leave.LeaveDtos.PageOfLeaveRequest;
import com.acme.hrms.leave.LeaveDtos.PendingLeaveApproval;
import com.acme.hrms.leave.LeaveDtos.TeamCalendarEntry;
import com.acme.hrms.validation.dto.leave.LeaveApproveRequest;
import com.acme.hrms.validation.dto.leave.LeaveCancelRequest;
import com.acme.hrms.validation.dto.leave.LeaveRejectRequest;
import com.acme.hrms.validation.dto.leave.LeaveRequestCreateRequest;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/leave/requests/**}, {@code /api/leave/approvals/pending} and {@code
 * /api/leave/team-calendar} of contracts/p2-leave/openapi.yaml. Bodies are validated inside the
 * service (after existence / scoping) so the frozen evaluation order holds. The {@code
 * /api/leave/admin/**} routes are declared in the contract but deferred to P5 and deliberately not
 * mounted here.
 */
@RestController
public class LeaveRequestController {

  private final LeaveRequestService service;

  public LeaveRequestController(LeaveRequestService service) {
    this.service = service;
  }

  @GetMapping("/api/leave/requests/mine")
  @PreAuthorize("isAuthenticated()")
  public List<LeaveRequest> listMyLeaveRequests(
      @RequestParam(required = false) String status, @RequestParam(required = false) Integer year) {
    return service.listMine(CurrentCaller.require(), status, year);
  }

  @GetMapping("/api/leave/requests")
  @PreAuthorize("hasAuthority('LEAVE:VIEW_ALL')")
  public PageOfLeaveRequest listLeaveRequestsForEmployee(
      @RequestParam long empId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer year,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.listForEmployee(empId, status, year, page, size);
  }

  @PostMapping("/api/leave/requests")
  @PreAuthorize("hasAuthority('LEAVE:CREATE')")
  public ResponseEntity<LeaveRequest> submitLeaveRequest(
      @RequestBody LeaveRequestCreateRequest body) {
    LeaveRequest created = service.submit(body, CurrentCaller.require());
    return ResponseEntity.created(URI.create("/api/leave/requests/" + created.requestId()))
        .body(created);
  }

  @GetMapping("/api/leave/requests/{id}")
  @PreAuthorize("isAuthenticated()")
  public LeaveRequest getLeaveRequest(@PathVariable long id) {
    return service.get(id, CurrentCaller.require());
  }

  @PostMapping("/api/leave/requests/{id}/cancel")
  @PreAuthorize("isAuthenticated()")
  public LeaveRequest cancelLeaveRequest(
      @PathVariable long id, @RequestBody(required = false) LeaveCancelRequest body) {
    return service.cancel(id, body, CurrentCaller.require());
  }

  @PostMapping("/api/leave/requests/{id}/approve")
  @PreAuthorize("isAuthenticated()")
  public LeaveRequest approveLeaveRequest(
      @PathVariable long id, @RequestBody(required = false) LeaveApproveRequest body) {
    return service.approve(id, body, CurrentCaller.require());
  }

  @PostMapping("/api/leave/requests/{id}/reject")
  @PreAuthorize("isAuthenticated()")
  public LeaveRequest rejectLeaveRequest(
      @PathVariable long id, @RequestBody(required = false) LeaveRejectRequest body) {
    return service.reject(id, body, CurrentCaller.require());
  }

  @GetMapping("/api/leave/approvals/pending")
  @PreAuthorize("isAuthenticated()")
  public List<PendingLeaveApproval> listPendingLeaveApprovals() {
    return service.pendingApprovals(CurrentCaller.require());
  }

  @GetMapping("/api/leave/team-calendar")
  @PreAuthorize("isAuthenticated()")
  public List<TeamCalendarEntry> getTeamLeaveCalendar(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return service.teamCalendar(CurrentCaller.require(), from, to);
  }
}
