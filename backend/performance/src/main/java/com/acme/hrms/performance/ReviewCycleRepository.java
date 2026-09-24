package com.acme.hrms.performance;

import com.acme.hrms.performance.PerformanceDtos.ReviewCycle;
import com.acme.hrms.validation.dto.performance.ReviewCycleRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Sole writer of {@code review_cycles} (ARCH-01/02). */
@Repository
public class ReviewCycleRepository {

  static final String COLUMNS =
      "cycle_id, cycle_name, cycle_year, start_date, end_date, self_review_due,"
          + " manager_review_due, calibration_due, status, created_by, created_date, modified_by,"
          + " modified_date";

  static final RowMapper<ReviewCycle> MAPPER =
      (rs, i) ->
          new ReviewCycle(
              rs.getLong("cycle_id"),
              rs.getString("cycle_name"),
              rs.getInt("cycle_year"),
              rs.getObject("start_date", LocalDate.class),
              rs.getObject("end_date", LocalDate.class),
              rs.getObject("self_review_due", LocalDate.class),
              rs.getObject("manager_review_due", LocalDate.class),
              rs.getObject("calibration_due", LocalDate.class),
              rs.getString("status"),
              rs.getString("created_by"),
              rs.getObject("created_date", LocalDateTime.class),
              rs.getString("modified_by"),
              rs.getObject("modified_date", LocalDateTime.class));

  private final JdbcTemplate jdbc;

  public ReviewCycleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ReviewCycle> findById(long cycleId) {
    List<ReviewCycle> rows =
        jdbc.query("select " + COLUMNS + " from review_cycles where cycle_id = ?", MAPPER, cycleId);
    return rows.stream().findFirst();
  }

  /** {@code statuses} are validated enum tokens; {@code orderBy} is one of the frozen sorts. */
  public List<ReviewCycle> list(List<String> statuses, String orderBy) {
    String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
    return jdbc.query(
        "select "
            + COLUMNS
            + " from review_cycles where status in ("
            + placeholders
            + ") order by "
            + orderBy
            + ", cycle_id desc",
        MAPPER,
        statuses.toArray());
  }

  public long insert(ReviewCycleRequest req, String createdBy) {
    Long id = jdbc.queryForObject("select nextval('seq_review_cycle')", Long.class);
    jdbc.update(
        "insert into review_cycles (cycle_id, cycle_name, cycle_year, start_date, end_date,"
            + " self_review_due, manager_review_due, calibration_due, status, created_by)"
            + " values (?,?,?,?,?,?,?,?,'DRAFT',?)",
        id,
        req.getCycleName(),
        req.getCycleYear(),
        req.getStartDate(),
        req.getEndDate(),
        req.getSelfReviewDue(),
        req.getManagerReviewDue(),
        req.getCalibrationDue(),
        createdBy);
    return id;
  }

  /** Full replacement of the editable fields; the caller has already checked {@code DRAFT}. */
  public int update(long cycleId, ReviewCycleRequest req, String modifiedBy) {
    return jdbc.update(
        "update review_cycles set cycle_name = ?, cycle_year = ?, start_date = ?, end_date = ?,"
            + " self_review_due = ?, manager_review_due = ?, calibration_due = ?, modified_by = ?,"
            + " modified_date = current_timestamp where cycle_id = ? and status = 'DRAFT'",
        req.getCycleName(),
        req.getCycleYear(),
        req.getStartDate(),
        req.getEndDate(),
        req.getSelfReviewDue(),
        req.getManagerReviewDue(),
        req.getCalibrationDue(),
        modifiedBy,
        cycleId);
  }

  /**
   * Guarded transition: {@code UPDATE … WHERE cycle_id = ? AND status IN (from)}. Returns the
   * number of rows affected (0 = the legacy {@code SQL%ROWCOUNT = 0} → {@code -20401}).
   */
  public int transition(long cycleId, List<String> from, String to, String modifiedBy) {
    List<Object> args = new ArrayList<>();
    args.add(to);
    args.add(modifiedBy);
    args.add(cycleId);
    args.addAll(from);
    return jdbc.update(
        "update review_cycles set status = ?, modified_by = ?, modified_date = current_timestamp"
            + " where cycle_id = ? and status in ("
            + String.join(",", from.stream().map(s -> "?").toList())
            + ")",
        args.toArray());
  }

  static String json(ReviewCycle c) {
    return "{\"cycleName\":"
        + quote(c.cycleName())
        + ",\"cycleYear\":"
        + c.cycleYear()
        + ",\"startDate\":"
        + quote(String.valueOf(c.startDate()))
        + ",\"endDate\":"
        + quote(String.valueOf(c.endDate()))
        + ",\"status\":"
        + quote(c.status())
        + "}";
  }

  static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
