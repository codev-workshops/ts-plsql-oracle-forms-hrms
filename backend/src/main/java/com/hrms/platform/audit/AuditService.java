package com.hrms.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Audit write path (legacy PKG_AUDIT.log_change and the AUDIT_* triggers, which the
 * modern app replaces with explicit service calls). Values are stored as JSON text.
 * Runs in the caller's transaction (inventory §9: legacy autonomous logging is not kept).
 */
@Service
public class AuditService {

    public enum Action { INSERT, UPDATE, DELETE }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String tableName, long recordId, Action action, Object oldValues, Object newValues,
                       String changedBy) {
        jdbc.update("""
                INSERT INTO audit_log (table_name, record_id, action_type, old_values, new_values, changed_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tableName.toUpperCase(), recordId, action.name(), toJson(oldValues), toJson(newValues),
                changedBy);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
