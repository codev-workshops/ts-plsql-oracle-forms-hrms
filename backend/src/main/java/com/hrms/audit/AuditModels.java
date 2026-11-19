package com.hrms.audit;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Audit API payloads (openapi-v1.yaml, tag "audit"). */
public final class AuditModels {

    private AuditModels() {
    }

    public enum AuditAction { INSERT, UPDATE, DELETE }

    public record AuditEntry(long auditId, String tableName, long recordId, String action, JsonNode oldValues,
                             JsonNode newValues, String changedBy, LocalDateTime changedDate) { }

    public record AuditSearch(String tableName, Long recordId, AuditAction action, String changedBy,
                              LocalDate from, LocalDate to, int limit) { }

    public record PurgeRequest(@Min(30) Integer retentionDays) { }

    public record BatchResult(String operation, int rowsAffected) { }
}
