package com.acme.hrms.performance;

import com.acme.hrms.performance.PerformanceDtos.PerformanceGoal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Sole writer of {@code performance_goals}. */
@Repository
public class PerformanceGoalRepository {

  private static final String SELECT =
      "select goal_id, review_id, emp_id, goal_title, goal_description, goal_category,"
          + " weight_pct, target_date, status, progress_pct, self_rating, manager_rating,"
          + " comments, created_by, created_date, modified_by, modified_date from"
          + " performance_goals";

  static final RowMapper<PerformanceGoal> MAPPER =
      (rs, i) ->
          new PerformanceGoal(
              rs.getLong("goal_id"),
              rs.getLong("review_id"),
              rs.getLong("emp_id"),
              rs.getString("goal_title"),
              rs.getString("goal_description"),
              rs.getString("goal_category"),
              rs.getBigDecimal("weight_pct"),
              rs.getObject("target_date", LocalDate.class),
              rs.getString("status"),
              rs.getBigDecimal("progress_pct"),
              rs.getBigDecimal("self_rating"),
              rs.getBigDecimal("manager_rating"),
              rs.getString("comments"),
              rs.getString("created_by"),
              rs.getObject("created_date", LocalDateTime.class),
              rs.getString("modified_by"),
              rs.getObject("modified_date", LocalDateTime.class));

  private final JdbcTemplate jdbc;

  public PerformanceGoalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<PerformanceGoal> findById(long goalId) {
    return jdbc.query(SELECT + " where goal_id = ?", MAPPER, goalId).stream().findFirst();
  }

  public List<PerformanceGoal> listByReview(long reviewId) {
    return jdbc.query(SELECT + " where review_id = ? order by goal_id", MAPPER, reviewId);
  }

  public long insert(
      long reviewId,
      long empId,
      String goalTitle,
      @Nullable String goalDescription,
      String goalCategory,
      BigDecimal weightPct,
      @Nullable LocalDate targetDate,
      String createdBy) {
    Long id = jdbc.queryForObject("select nextval('seq_perf_goal')", Long.class);
    jdbc.update(
        "insert into performance_goals (goal_id, review_id, emp_id, goal_title, goal_description,"
            + " goal_category, weight_pct, target_date, status, progress_pct, created_by) values"
            + " (?,?,?,?,?,?,?,?,'NOT_STARTED',0,?)",
        id,
        reviewId,
        empId,
        goalTitle,
        goalDescription,
        goalCategory,
        weightPct,
        targetDate,
        createdBy);
    return id;
  }

  /** {@code PKG_PERFORMANCE.update_goal_progress} row update; status already derived. */
  public int updateProgress(
      long goalId,
      BigDecimal progressPct,
      String status,
      @Nullable String comments,
      String modifiedBy) {
    return jdbc.update(
        "update performance_goals set progress_pct = ?, status = ?, comments = coalesce(?,"
            + " comments), modified_by = ?, modified_date = current_timestamp where goal_id = ?",
        progressPct,
        status,
        comments,
        modifiedBy,
        goalId);
  }
}
