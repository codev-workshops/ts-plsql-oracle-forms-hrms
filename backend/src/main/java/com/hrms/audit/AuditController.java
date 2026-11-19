package com.hrms.audit;

import com.hrms.audit.AuditModels.AuditAction;
import com.hrms.audit.AuditModels.AuditEntry;
import com.hrms.audit.AuditModels.BatchResult;
import com.hrms.audit.AuditModels.PurgeRequest;
import com.hrms.platform.auth.CurrentUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditLogService auditLog;

    public AuditController(AuditLogService auditLog) {
        this.auditLog = auditLog;
    }

    @GetMapping("/entries")
    public List<AuditEntry> search(CurrentUser user,
                                   @RequestParam(required = false) String tableName,
                                   @RequestParam(required = false) Long recordId,
                                   @RequestParam(required = false) AuditAction action,
                                   @RequestParam(required = false) String changedBy,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate to,
                                   @RequestParam(required = false) Integer limit) {
        return auditLog.search(user, tableName, recordId, action, changedBy, from, to, limit);
    }

    @GetMapping("/entries/{auditId}")
    public AuditEntry get(CurrentUser user, @PathVariable long auditId) {
        return auditLog.get(user, auditId);
    }

    @GetMapping("/records/{tableName}/{recordId}")
    public List<AuditEntry> trail(CurrentUser user, @PathVariable String tableName, @PathVariable long recordId) {
        return auditLog.trail(user, tableName, recordId);
    }

    @PostMapping("/purge")
    public BatchResult purge(CurrentUser user, @Valid @RequestBody(required = false) PurgeRequest request) {
        return new BatchResult("PURGE_AUDIT_LOG",
                auditLog.purge(user, request == null ? null : request.retentionDays()));
    }
}
