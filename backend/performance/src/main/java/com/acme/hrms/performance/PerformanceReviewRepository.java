package com.acme.hrms.performance;

import com.acme.hrms.performance.PerformanceDtos.PageOfPerformanceReview;
import com.acme.hrms.performance.PerformanceDtos.PerformanceReview;
import com.acme.hrms.performance.PerformanceDtos.RatingDistributionRow;
import com.acme.hrms.performance.PerformanceDtos.TeamReviewRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Sole writer of {@code performance_reviews}; reads {@code employees} only for display names. */
@Repository
public class PerformanceReviewRepository {

  private static final String SELECT =
      "select r.review_id, r.cycle_id, r.emp_id, e.first_name || ' ' || e.last_name as"
          + " employee_name, r.reviewer_emp_id, m.first_name || ' ' || m.last_name as"
          + " reviewer_name, r.review_type, r.status, r.overall_rating, r.rating_label,"
          + " r.self_assessment, r.manager_assessment, r.strengths, r.areas_for_improvement,"
          + " r.development_plan, r.employee_comments, r.employee_ack_date, r.calibrated_rating,"
          + " r.calibration_notes, r.created_by, r.created_date, r.modified_by, r.modified_date"
          + " from performance_reviews r join employees e on e.emp_id = r.emp_id join employees m"
          + " on m.emp_id = r.reviewer_emp_id";

  static final RowMapper<PerformanceReview> MAPPER =
      (rs, i) -> {
        Timestamp ack = rs.getTimestamp("employee_ack_date");
        return new PerformanceReview(
            rs.getLong("review_id"),
            rs.getLong("cycle_id"),
            rs.getLong("emp_id"),
            rs.getString("employee_name"),
            rs.getLong("reviewer_emp_id"),
            rs.getString("reviewer_name"),
            rs.getString("review_type"),
            rs.getString("status"),
            rs.getBigDecimal("overall_rating"),
            rs.getString("rating_label"),
            rs.getString("self_assessment"),
            rs.getString("manager_assessment"),
            rs.getString("strengths"),
            rs.getString("areas_for_improvement"),
            rs.getString("development_plan"),
            rs.getString("employee_comments"),
            ack == null ? null : ack.toLocalDateTime().toLocalDate(),
            rs.getBigDecimal("calibrated_rating"),
            rs.getString("calibration_notes"),
            rs.getString("created_by"),
            rs.getObject("created_date", LocalDateTime.class),
            rs.getString("modified_by"),
            rs.getObject("modified_date", LocalDateTime.class));
      };

  private final JdbcTemplate jdbc;

  public PerformanceReviewRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<PerformanceReview> findById(long reviewId) {
    return jdbc.query(SELECT + " where r.review_id = ?", MAPPER, reviewId).stream().findFirst();
  }

  public PageOfPerformanceReview pageByCycle(
      long cycleId, List<String> statuses, int page, int size) {
    StringBuilder where = new StringBuilder(" where r.cycle_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(cycleId);
    if (!statuses.isEmpty()) {
      where
          .append(" and r.status in (")
          .append(String.join(",", statuses.stream().map(s -> "?").toList()))
          .append(")");
      args.addAll(statuses);
    }
    Long total =
        jdbc.queryForObject(
            "select count(*) from performance_reviews r" + where, Long.class, args.toArray());
    long totalElements = total == null ? 0 : total;
    args.add(size);
    args.add((long) page * size);
    List<PerformanceReview> content =
        jdbc.query(
            SELECT + where + " order by e.last_name, r.review_id limit ? offset ?",
            MAPPER,
            args.toArray());
    return new PageOfPerformanceReview(
        content, page, size, totalElements, (int) Math.ceil(totalElements / (double) size));
  }

  public List<PerformanceReview> listMine(long empId, @Nullable Long cycleId) {
    if (cycleId == null) {
      return jdbc.query(
          SELECT
              + " join review_cycles c on c.cycle_id = r.cycle_id where r.emp_id = ? order by"
              + " c.cycle_year desc, r.review_id desc",
          MAPPER,
          empId);
    }
    return jdbc.query(
        SELECT
            + " join review_cycles c on c.cycle_id = r.cycle_id where r.emp_id = ? and"
            + " r.cycle_id = ? order by c.cycle_year desc, r.review_id desc",
        MAPPER,
        empId,
        cycleId);
  }

  /**
   * PERF-05: one set-based {@code INSERT … SELECT} over the active employees with a manager,
   * skipping those that already have a review in the cycle. Returns the created review ids (the
   * audit/notification fan-out needs them) - the eligible count minus this size is "skipped".
   */
  public List<Long> generateForCycle(long cycleId, String createdBy) {
    return jdbc.queryForList(
        "insert into performance_reviews (review_id, cycle_id, emp_id, reviewer_emp_id,"
            + " review_type, status, created_by) select nextval('seq_perf_review'), ?, e.emp_id,"
            + " e.manager_emp_id, 'ANNUAL', 'NOT_STARTED', ? from employees e where"
            + " e.employment_status = 'ACTIVE' and e.manager_emp_id is not null and not exists"
            + " (select 1 from performance_reviews x where x.cycle_id = ? and x.emp_id = e.emp_id)"
            + " order by e.emp_id returning review_id",
        Long.class,
        cycleId,
        createdBy,
        cycleId);
  }

  public int countEligible() {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from employees where employment_status = 'ACTIVE' and"
                + " manager_emp_id is not null",
            Integer.class);
    return n == null ? 0 : n;
  }

  /** {@code NOT_STARTED | SELF_REVIEW → MANAGER_REVIEW}; 0 rows = wrong source status. */
  public int submitSelfAssessment(long reviewId, String selfAssessment, String modifiedBy) {
    return jdbc.update(
        "update performance_reviews set self_assessment = ?, status = 'MANAGER_REVIEW',"
            + " modified_by = ?, modified_date = current_timestamp where review_id = ? and status"
            + " in ('NOT_STARTED','SELF_REVIEW')",
        selfAssessment,
        modifiedBy,
        reviewId);
  }

  /** {@code MANAGER_REVIEW | MEETING_SCHEDULED → COMPLETED}; 0 rows = wrong source status. */
  public int submitManagerReview(
      long reviewId,
      BigDecimal overallRating,
      String ratingLabel,
      String managerAssessment,
      @Nullable String strengths,
      @Nullable String areasForImprovement,
      @Nullable String developmentPlan,
      String modifiedBy) {
    return jdbc.update(
        "update performance_reviews set overall_rating = ?, rating_label = ?,"
            + " manager_assessment = ?, strengths = ?, areas_for_improvement = ?,"
            + " development_plan = ?, status = 'COMPLETED', modified_by = ?, modified_date ="
            + " current_timestamp where review_id = ? and status in"
            + " ('MANAGER_REVIEW','MEETING_SCHEDULED')",
        overallRating,
        ratingLabel,
        managerAssessment,
        strengths,
        areasForImprovement,
        developmentPlan,
        modifiedBy,
        reviewId);
  }

  /** {@code COMPLETED → ACKNOWLEDGED}; 0 rows = wrong source status. */
  public int acknowledge(long reviewId, @Nullable String employeeComments, String modifiedBy) {
    return jdbc.update(
        "update performance_reviews set employee_comments = ?, employee_ack_date = ?, status ="
            + " 'ACKNOWLEDGED', modified_by = ?, modified_date = current_timestamp where review_id"
            + " = ? and status = 'COMPLETED'",
        employeeComments,
        LocalDate.now().atStartOfDay(),
        modifiedBy,
        reviewId);
  }

  /** {@code PKG_PERFORMANCE.get_team_reviews(p_manager_id := jwt.empId, p_cycle_id)}. */
  public List<TeamReviewRow> teamReviews(long managerEmpId, long cycleId) {
    return jdbc.query(
        "select r.review_id, r.emp_id, e.first_name || ' ' || e.last_name as employee_name,"
            + " j.job_title, d.dept_name, r.status, r.overall_rating, r.rating_label from"
            + " performance_reviews r join employees e on e.emp_id = r.emp_id join job_titles j on"
            + " j.job_id = e.job_id join departments d on d.dept_id = e.dept_id where"
            + " r.reviewer_emp_id = ? and r.cycle_id = ? order by e.last_name, r.review_id",
        (rs, i) ->
            new TeamReviewRow(
                rs.getLong("review_id"),
                rs.getLong("emp_id"),
                rs.getString("employee_name"),
                rs.getString("job_title"),
                rs.getString("dept_name"),
                rs.getString("status"),
                rs.getBigDecimal("overall_rating"),
                rs.getString("rating_label")),
        managerEmpId,
        cycleId);
  }

  /** {@code PKG_PERFORMANCE.get_rating_distribution(p_cycle_id, p_dept_id)}. */
  public List<RatingDistributionRow> ratingDistribution(long cycleId, @Nullable Long deptId) {
    String sql =
        "select r.rating_label, count(*) as cnt, round(count(*) * 100.0 / sum(count(*)) over (),"
            + " 1) as pct from performance_reviews r join employees e on e.emp_id = r.emp_id where"
            + " r.cycle_id = ? and r.overall_rating is not null"
            + (deptId == null ? "" : " and e.dept_id = ?")
            + " group by r.rating_label order by min(r.overall_rating) desc";
    Object[] args = deptId == null ? new Object[] {cycleId} : new Object[] {cycleId, deptId};
    return jdbc.query(
        sql,
        (rs, i) ->
            new RatingDistributionRow(
                rs.getString("rating_label"), rs.getLong("cnt"), rs.getBigDecimal("pct")),
        args);
  }
}
