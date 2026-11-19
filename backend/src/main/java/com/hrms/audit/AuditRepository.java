package com.hrms.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrms.audit.AuditModels.AuditEntry;
import com.hrms.audit.AuditModels.AuditSearch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read and purge side of AUDIT_LOG (legacy PKG_AUDIT.get_change_history / purge_old_records). */
@Repository
public class AuditRepository {

    private static final String SELECT = """
            SELECT audit_id, table_name, record_id, action_type, old_values, new_values, changed_by, changed_date
            FROM audit_log
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<AuditEntry> search(AuditSearch search) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (search.tableName() != null) {
            sql.append(" AND table_name = ?");
            args.add(search.tableName());
        }
        if (search.recordId() != null) {
            sql.append(" AND record_id = ?");
            args.add(search.recordId());
        }
        if (search.action() != null) {
            sql.append(" AND action_type = ?");
            args.add(search.action().name());
        }
        if (search.changedBy() != null) {
            sql.append(" AND lower(changed_by) = lower(?)");
            args.add(search.changedBy());
        }
        if (search.from() != null) {
            sql.append(" AND changed_date >= ?");
            args.add(Timestamp.valueOf(search.from().atStartOfDay()));
        }
        if (search.to() != null) {
            sql.append(" AND changed_date < ?");
            args.add(Timestamp.valueOf(search.to().plusDays(1).atStartOfDay()));
        }
        sql.append(" ORDER BY changed_date DESC, audit_id DESC LIMIT ?");
        args.add(search.limit());
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    public Optional<AuditEntry> find(long auditId) {
        return jdbc.query(SELECT + " WHERE audit_id = ?", this::map, auditId).stream().findFirst();
    }

    public List<AuditEntry> trail(String tableName, long recordId) {
        return jdbc.query(SELECT + " WHERE table_name = ? AND record_id = ? ORDER BY changed_date, audit_id",
                this::map, tableName, recordId);
    }

    public int purgeOlderThan(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM audit_log WHERE changed_date < ?", Timestamp.valueOf(cutoff));
    }

    private AuditEntry map(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(rs.getLong("audit_id"), rs.getString("table_name"), rs.getLong("record_id"),
                rs.getString("action_type"), values(rs.getString("old_values")), values(rs.getString("new_values")),
                rs.getString("changed_by"), rs.getTimestamp("changed_date").toLocalDateTime());
    }

    /** Stored values are JSON text; anything that is not a JSON object is returned as {"value": text}. */
    private JsonNode values(String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (JsonProcessingException e) {
            // legacy free text: wrapped below
        }
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put("value", text);
        return wrapped;
    }
}
