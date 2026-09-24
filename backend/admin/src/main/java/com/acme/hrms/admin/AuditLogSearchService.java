package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.AuditLogPage;
import com.acme.hrms.admin.AdminDtos.AuditLogRow;
import com.acme.hrms.admin.AdminDtos.PageMeta;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.AuditLogSearchQuery;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Read side of AUDIT_LOG (HRMS_ADMIN audit block). */
@Service
public class AuditLogSearchService {
  private static final RowMapper<AuditLogRow> MAPPER =
      (rs, i) ->
          new AuditLogRow(
              rs.getLong("audit_id"),
              rs.getString("table_name"),
              rs.getLong("record_id"),
              rs.getString("action_type"),
              rs.getString("old_values"),
              rs.getString("new_values"),
              rs.getString("changed_by"),
              rs.getObject("changed_date", LocalDateTime.class),
              rs.getString("ip_address"),
              rs.getString("session_id"));

  private final NamedParameterJdbcTemplate jdbc;

  public AuditLogSearchService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public AuditLogPage search(AuditLogSearchQuery q) {
    if (q.getFrom() != null && q.getTo() != null) {
      if (q.getTo().isBefore(q.getFrom())) {
        throw new HrmsException(ErrorCode.VALIDATION_FAILED, "to must be on or after from", "to");
      }
      if (ChronoUnit.DAYS.between(q.getFrom(), q.getTo()) > 366) {
        throw new HrmsException(
            ErrorCode.VALIDATION_FAILED, "Date range must not exceed 366 days", "to");
      }
    }
    int page = q.getPage() == null ? 0 : q.getPage();
    int size = q.getSize() == null ? 50 : q.getSize();
    StringBuilder where = new StringBuilder(" where 1=1");
    Map<String, Object> p = new HashMap<>();
    if (q.getTableName() != null && !q.getTableName().isBlank()) {
      where.append(" and lower(a.table_name) = :table");
      p.put("table", q.getTableName());
    }
    if (q.getRecordId() != null) {
      where.append(" and a.record_id = :rec");
      p.put("rec", q.getRecordId().longValue());
    }
    if (q.getActionType() != null) {
      where.append(" and a.action_type = :action");
      p.put("action", q.getActionType());
    }
    if (q.getChangedBy() != null && !q.getChangedBy().isBlank()) {
      where.append(" and a.changed_by = :by");
      p.put("by", q.getChangedBy());
    }
    if (q.getFrom() != null) {
      where.append(" and a.changed_date >= :from");
      p.put("from", q.getFrom().atStartOfDay());
    }
    if (q.getTo() != null) {
      where.append(" and a.changed_date < :to");
      p.put("to", q.getTo().plusDays(1).atStartOfDay());
    }
    Long total = jdbc.queryForObject("select count(*) from audit_log a" + where, p, Long.class);
    p.put("limit", size);
    p.put("offset", (long) page * size);
    List<AuditLogRow> rows =
        jdbc.query(
            "select a.* from audit_log a"
                + where
                + " order by a.changed_date desc, a.audit_id desc limit :limit offset :offset",
            p,
            MAPPER);
    return new AuditLogPage(rows, PageMeta.of(page, size, total == null ? 0 : total));
  }
}
