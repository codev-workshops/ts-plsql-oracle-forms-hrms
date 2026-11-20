package com.hrms.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes {@code audit_log} rows in the caller's transaction (D-04, D-08). A failed write fails the
 * business transaction; nothing is swallowed (replaces PKG_AUDIT.log_action).
 */
@Component
public class AuditWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuditWriter(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void write(String table, long recordId, String action, Map<String, ?> oldValues,
            Map<String, ?> newValues, String actor, String ipAddress, Long sessionId) {
        jdbc.sql("""
                INSERT INTO hrms.audit_log (table_name, record_id, action_type, old_values, new_values,
                                            changed_by, changed_date, ip_address, session_id)
                VALUES (:table, :id, :action, CAST(:old AS jsonb), CAST(:new AS jsonb), :actor, :at, :ip, :sid)
                """)
                .param("table", table)
                .param("id", recordId)
                .param("action", action)
                .param("old", json(oldValues))
                .param("new", json(newValues))
                .param("actor", actor)
                .param("at", OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS))
                .param("ip", ipAddress)
                .param("sid", sessionId == null ? null : sessionId.toString())
                .update();
    }

    private String json(Map<String, ?> values) {
        if (values == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit payload is not serializable", e);
        }
    }
}
