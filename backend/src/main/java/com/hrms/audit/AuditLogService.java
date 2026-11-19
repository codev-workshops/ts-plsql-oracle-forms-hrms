package com.hrms.audit;

import com.hrms.audit.AuditModels.AuditAction;
import com.hrms.audit.AuditModels.AuditEntry;
import com.hrms.audit.AuditModels.AuditSearch;
import com.hrms.platform.audit.AuditService;
import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.param.ParameterService;
import com.hrms.platform.security.PermissionService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit viewer (PKG_AUDIT read/purge). Admin only: grade >= 8 for every operation, stricter than the
 * generic VIEW rule (grade >= 5) because entries carry other modules' data (salaries, leave, logins).
 * Writing stays in the shared {@link AuditService}.
 */
@Service
public class AuditLogService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;
    public static final int DEFAULT_RETENTION_DAYS = 2555;
    public static final int MIN_RETENTION_DAYS = 30;

    private final AuditRepository repo;
    private final AuditService audit;
    private final ParameterService params;
    private final Clock clock;

    public AuditLogService(AuditRepository repo, AuditService audit, ParameterService params, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.params = params;
        this.clock = clock;
    }

    public List<AuditEntry> search(CurrentUser user, String tableName, Long recordId, AuditAction action,
                                   String changedBy, LocalDate from, LocalDate to, Integer limit) {
        requireAdmin(user);
        int rows = limit == null ? DEFAULT_LIMIT : limit;
        if (rows < 1 || rows > MAX_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION, "limit must be between 1 and " + MAX_LIMIT);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(ErrorCode.VALIDATION, "from must be on or before to");
        }
        return repo.search(new AuditSearch(normalizeTable(tableName), recordId, action, blankToNull(changedBy),
                from, to, rows));
    }

    public AuditEntry get(CurrentUser user, long auditId) {
        requireAdmin(user);
        return repo.find(auditId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Audit entry not found: " + auditId));
    }

    public List<AuditEntry> trail(CurrentUser user, String tableName, long recordId) {
        requireAdmin(user);
        String table = normalizeTable(tableName);
        if (table == null) {
            throw new ApiException(ErrorCode.VALIDATION, "tableName is required");
        }
        return repo.trail(table, recordId);
    }

    /** Deletes entries older than the retention window; the purge itself is audited (not covered by it). */
    @Transactional
    public int purge(CurrentUser user, Integer retentionDays) {
        requireAdmin(user);
        int days = retentionDays != null ? retentionDays
                : params.getInt("AUDIT", "RETENTION_DAYS", DEFAULT_RETENTION_DAYS);
        if (days < MIN_RETENTION_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION, "retentionDays must be at least " + MIN_RETENTION_DAYS);
        }
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(days);
        int deleted = repo.purgeOlderThan(cutoff);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("retentionDays", days);
        details.put("cutoff", cutoff.toString());
        details.put("rowsDeleted", deleted);
        audit.record("AUDIT_LOG", 0, AuditService.Action.DELETE, null, details, user.username());
        return deleted;
    }

    private static void requireAdmin(CurrentUser user) {
        if (user.gradeLevel() < PermissionService.FULL_ACCESS_GRADE) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE,
                    "Insufficient privilege: AUDIT requires grade " + PermissionService.FULL_ACCESS_GRADE);
        }
    }

    private static String normalizeTable(String tableName) {
        String value = blankToNull(tableName);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
