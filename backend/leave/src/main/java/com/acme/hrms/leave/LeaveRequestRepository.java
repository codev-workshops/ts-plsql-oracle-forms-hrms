package com.acme.hrms.leave;

import com.acme.hrms.leave.LeaveDtos.LeaveRequest;
import com.acme.hrms.leave.LeaveDtos.PendingLeaveApproval;
import com.acme.hrms.leave.LeaveDtos.TeamCalendarEntry;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Owner of {@code leave_requests} (ARCH-02). Plain SQL over JdbcTemplate; no triggers. */
@Repository
public class LeaveRequestRepository {

  private static final String SELECT =
      "select r.request_id, r.emp_id, e.first_name || ' ' || e.last_name as emp_name,"
          + " r.leave_type_id, lt.leave_type_code, lt.leave_type_name, r.start_date, r.end_date,"
          + " r.total_days, r.half_day_flag, r.half_day_period, r.status, r.reason,"
          + " r.approver_emp_id, a.first_name || ' ' || a.last_name as approver_name,"
          + " r.approval_date, r.approval_comments, r.created_date, r.modified_date"
          + " from leave_requests r"
          + " join employees e on e.emp_id = r.emp_id"
          + " join leave_types lt on lt.leave_type_id = r.leave_type_id"
          + " left join employees a on a.emp_id = r.approver_emp_id";

  private static final RowMapper<LeaveRequest> MAPPER =
      (rs, i) ->
          new LeaveRequest(
              rs.getLong("request_id"),
              rs.getLong("emp_id"),
              rs.getString("emp_name"),
              rs.getInt("leave_type_id"),
              rs.getString("leave_type_code"),
              rs.getString("leave_type_name"),
              rs.getObject("start_date", LocalDate.class),
              rs.getObject("end_date", LocalDate.class),
              rs.getBigDecimal("total_days"),
              "Y".equals(rs.getString("half_day_flag")),
              rs.getString("half_day_period"),
              rs.getString("status"),
              rs.getString("reason"),
              nullableLong(rs, "approver_emp_id"),
              rs.getString("approver_name"),
              rs.getObject("approval_date", LocalDateTime.class),
              rs.getString("approval_comments"),
              rs.getObject("created_date", LocalDateTime.class),
              rs.getObject("modified_date", LocalDateTime.class));

  private final JdbcTemplate jdbc;

  public LeaveRequestRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Nullable
  private static Long nullableLong(ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }

  public Optional<LeaveRequest> find(long requestId) {
    return jdbc.query(SELECT + " where r.request_id = ?", MAPPER, requestId).stream().findFirst();
  }

  public LeaveRequest get(long requestId) {
    return find(requestId).orElseThrow();
  }

  /** {@code ORDER BY created_date DESC, request_id DESC} with optional status / year filters. */
  public List<LeaveRequest> listForEmployee(
      long empId, List<String> statuses, @Nullable Integer year, int offset, int limit) {
    StringBuilder sql = new StringBuilder(SELECT).append(" where r.emp_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(empId);
    filters(sql, args, statuses, year);
    sql.append(" order by r.created_date desc, r.request_id desc offset ? limit ?");
    args.add(offset);
    args.add(limit);
    return jdbc.query(sql.toString(), MAPPER, args.toArray());
  }

  public long countForEmployee(long empId, List<String> statuses, @Nullable Integer year) {
    StringBuilder sql =
        new StringBuilder("select count(*) from leave_requests r where r.emp_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(empId);
    filters(sql, args, statuses, year);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0 : n;
  }

  private static void filters(
      StringBuilder sql, List<Object> args, List<String> statuses, @Nullable Integer year) {
    if (!statuses.isEmpty()) {
      sql.append(" and r.status in (")
          .append(String.join(",", statuses.stream().map(s -> "?").toList()))
          .append(")");
      args.addAll(statuses);
    }
    if (year != null) {
      sql.append(" and extract(year from r.start_date) = ?");
      args.add(year);
    }
  }

  public record NewRequest(
      long empId,
      int leaveTypeId,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal totalDays,
      boolean halfDay,
      @Nullable String halfDayPeriod,
      String status,
      @Nullable String reason,
      @Nullable Long approverEmpId,
      @Nullable LocalDateTime approvalDate,
      String createdBy) {}

  public long insert(NewRequest r) {
    Long id =
        jdbc.queryForObject(
            "insert into leave_requests (request_id, emp_id, leave_type_id, start_date, end_date,"
                + " total_days, half_day_flag, half_day_period, status, reason, approver_emp_id,"
                + " approval_date, created_by, created_date)"
                + " values (nextval('seq_leave_request'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " current_timestamp) returning request_id",
            Long.class,
            r.empId(),
            r.leaveTypeId(),
            r.startDate(),
            r.endDate(),
            r.totalDays(),
            r.halfDay() ? "Y" : "N",
            r.halfDayPeriod(),
            r.status(),
            r.reason(),
            r.approverEmpId(),
            r.approvalDate(),
            r.createdBy());
    return id == null ? 0 : id;
  }

  /**
   * BUG-06: two half days on the same single date with different periods do not overlap. Any other
   * PENDING / APPROVED request whose range intersects {@code [start, end]} does.
   */
  public boolean overlaps(
      long empId, LocalDate start, LocalDate end, boolean halfDay, @Nullable String period) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from leave_requests where emp_id = ?"
                + " and status in ('PENDING','APPROVED')"
                + " and start_date <= ? and end_date >= ?"
                + " and not (? and half_day_flag = 'Y' and start_date = ? and end_date = ?"
                + "          and half_day_period is distinct from ?)",
            Integer.class,
            empId,
            end,
            start,
            halfDay,
            start,
            start,
            period);
    return n != null && n > 0;
  }

  public int cancel(long requestId, String reason, String modifiedBy) {
    return jdbc.update(
        "update leave_requests set status = 'CANCELLED', reason = ?, cancel_reason = ?,"
            + " cancelled_date = current_timestamp, modified_by = ?,"
            + " modified_date = current_timestamp where request_id = ?"
            + " and status in ('PENDING', 'APPROVED')",
        reason,
        reason,
        modifiedBy,
        requestId);
  }

  public int decide(
      long requestId, String status, long approverEmpId, @Nullable String comments, String by) {
    return jdbc.update(
        "update leave_requests set status = ?, approver_emp_id = ?,"
            + " approval_date = current_timestamp, approval_comments = ?, modified_by = ?,"
            + " modified_date = current_timestamp where request_id = ? and status = 'PENDING'",
        status,
        approverEmpId,
        comments,
        by,
        requestId);
  }

  public List<PendingLeaveApproval> pendingFor(long approverEmpId) {
    return jdbc.query(
        "select r.request_id, r.emp_id, e.emp_number, e.first_name || ' ' || e.last_name as emp_name,"
            + " lt.leave_type_name, r.start_date, r.end_date, r.total_days, r.half_day_flag,"
            + " r.half_day_period, r.reason, r.created_date"
            + " from leave_requests r"
            + " join employees e on e.emp_id = r.emp_id"
            + " join leave_types lt on lt.leave_type_id = r.leave_type_id"
            + " where r.approver_emp_id = ? and r.status = 'PENDING'"
            + " order by r.created_date, r.request_id",
        (rs, i) ->
            new PendingLeaveApproval(
                rs.getLong("request_id"),
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                rs.getString("emp_name"),
                rs.getString("leave_type_name"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBigDecimal("total_days"),
                "Y".equals(rs.getString("half_day_flag")),
                rs.getString("half_day_period"),
                rs.getString("reason"),
                rs.getObject("created_date", LocalDateTime.class)),
        approverEmpId);
  }

  public List<TeamCalendarEntry> teamCalendar(long managerEmpId, LocalDate from, LocalDate to) {
    return jdbc.query(
        "select r.request_id, r.emp_id, e.first_name || ' ' || e.last_name as emp_name,"
            + " lt.leave_type_name, r.start_date, r.end_date, r.total_days, r.half_day_flag,"
            + " r.half_day_period, r.status"
            + " from leave_requests r"
            + " join employees e on e.emp_id = r.emp_id"
            + " join leave_types lt on lt.leave_type_id = r.leave_type_id"
            + " where e.manager_emp_id = ? and r.status in ('APPROVED','TAKEN')"
            + " and r.start_date <= ? and r.end_date >= ?"
            + " order by r.start_date, e.last_name, e.first_name, r.request_id",
        (rs, i) ->
            new TeamCalendarEntry(
                rs.getLong("request_id"),
                rs.getLong("emp_id"),
                rs.getString("emp_name"),
                rs.getString("leave_type_name"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBigDecimal("total_days"),
                "Y".equals(rs.getString("half_day_flag")),
                rs.getString("half_day_period"),
                rs.getString("status")),
        managerEmpId,
        to,
        from);
  }
}
