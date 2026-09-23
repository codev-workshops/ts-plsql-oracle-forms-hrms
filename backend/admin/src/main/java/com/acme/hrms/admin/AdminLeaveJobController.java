package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.AuditLogPage;
import com.acme.hrms.admin.AdminDtos.BatchRunResult;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.validation.dto.admin.AccrualRunRequest;
import com.acme.hrms.validation.dto.admin.AuditLogSearchQuery;
import com.acme.hrms.validation.dto.admin.CarryoverRunRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Leave batch triggers, their P2 301 aliases and the audit-log search. */
@RestController
public class AdminLeaveJobController {
  private static final String LEAVE_ADMIN = "hasAuthority('LEAVE:ADMIN')";

  private final LeaveJobTrigger trigger;
  private final AuditLogSearchService auditLog;

  public AdminLeaveJobController(LeaveJobTrigger trigger, AuditLogSearchService auditLog) {
    this.trigger = trigger;
    this.auditLog = auditLog;
  }

  @PostMapping("/api/admin/leave/accrual")
  @PreAuthorize(LEAVE_ADMIN)
  public ResponseEntity<BatchRunResult> runAccrual(
      @Valid @RequestBody(required = false) @Nullable AccrualRunRequest body) {
    BatchRunResult r =
        trigger.startAccrual(
            body == null ? null : body.getAccrualDate(), CurrentCaller.require().userId());
    return accepted(r);
  }

  @PostMapping("/api/admin/leave/carryover")
  @PreAuthorize(LEAVE_ADMIN)
  public ResponseEntity<BatchRunResult> runCarryover(@Valid @RequestBody CarryoverRunRequest body) {
    BatchRunResult r = trigger.startCarryover(body.getYear(), CurrentCaller.require().userId());
    return accepted(r);
  }

  @GetMapping("/api/admin/leave/jobs/{jobId}")
  @PreAuthorize(LEAVE_ADMIN)
  public BatchRunResult getJob(@PathVariable UUID jobId) {
    return trigger.get(jobId);
  }

  @PostMapping("/api/leave/admin/accrual/run")
  @PreAuthorize(LEAVE_ADMIN)
  public ResponseEntity<Void> legacyAccrualAlias() {
    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
        .location(URI.create("/api/admin/leave/accrual"))
        .build();
  }

  @PostMapping("/api/leave/admin/carryover/run")
  @PreAuthorize(LEAVE_ADMIN)
  public ResponseEntity<Void> legacyCarryoverAlias() {
    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
        .location(URI.create("/api/admin/leave/carryover"))
        .build();
  }

  @GetMapping("/api/admin/audit-log")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public AuditLogPage searchAuditLog(@Valid @ModelAttribute AuditLogSearchQuery q) {
    return auditLog.search(q);
  }

  private static ResponseEntity<BatchRunResult> accepted(BatchRunResult r) {
    return ResponseEntity.accepted()
        .location(URI.create("/api/admin/leave/jobs/" + r.jobId()))
        .body(r);
  }
}
