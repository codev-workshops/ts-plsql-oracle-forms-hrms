package com.hrms.performance;

import com.hrms.performance.PerformanceModels.PerformanceGoal;
import com.hrms.performance.PerformanceModels.PerformanceReview;
import com.hrms.performance.PerformanceModels.RatingCount;
import com.hrms.performance.PerformanceModels.ReviewCycle;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class PerformanceRepository {

    private static final String CYCLE_SELECT = """
            SELECT cycle_id, cycle_name, cycle_year, start_date, end_date, self_review_due, manager_review_due, status
            FROM review_cycles
            """;

    private static final String REVIEW_SELECT = """
            SELECT pr.review_id, pr.cycle_id, pr.emp_id, e.first_name || ' ' || e.last_name AS employee_name,
                   pr.reviewer_emp_id, r.first_name || ' ' || r.last_name AS reviewer_name, pr.status,
                   pr.self_assessment, pr.self_rating, pr.manager_assessment, pr.overall_rating, pr.rating_label
            FROM performance_reviews pr
            JOIN employees e ON e.emp_id = pr.emp_id
            JOIN employees r ON r.emp_id = pr.reviewer_emp_id
            """;

    private static final String GOAL_SELECT = """
            SELECT goal_id, review_id, emp_id, goal_title, goal_description, weight_pct, target_date, progress_pct,
                   status
            FROM performance_goals
            """;

    private final JdbcTemplate jdbc;

    public PerformanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ cycles

    public List<ReviewCycle> cycles(String status) {
        if (status == null) {
            return jdbc.query(CYCLE_SELECT + " ORDER BY start_date DESC, cycle_id DESC", PerformanceRepository::mapCycle);
        }
        return jdbc.query(CYCLE_SELECT + " WHERE status = ? ORDER BY start_date DESC, cycle_id DESC",
                PerformanceRepository::mapCycle, status);
    }

    public Optional<ReviewCycle> findCycle(long cycleId) {
        return jdbc.query(CYCLE_SELECT + " WHERE cycle_id = ?", PerformanceRepository::mapCycle, cycleId)
                .stream().findFirst();
    }

    public long insertCycle(ReviewCycle c, int year, String user) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO review_cycles (cycle_name, cycle_year, start_date, end_date, self_review_due,
                                               manager_review_due, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                    """, new String[] {"cycle_id"});
            ps.setString(1, c.cycleName().trim());
            ps.setInt(2, year);
            ps.setObject(3, c.startDate());
            ps.setObject(4, c.endDate());
            ps.setObject(5, c.selfReviewDeadline());
            ps.setObject(6, c.managerReviewDeadline());
            ps.setString(7, user);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Conditional status change; returns rows updated (0 when the current status is not allowed). */
    public int setCycleStatus(long cycleId, String status, List<String> fromStatuses, String user) {
        String placeholders = String.join(",", fromStatuses.stream().map(s -> "?").toList());
        Object[] args = new Object[3 + fromStatuses.size()];
        args[0] = status;
        args[1] = user;
        args[2] = cycleId;
        for (int i = 0; i < fromStatuses.size(); i++) {
            args[3 + i] = fromStatuses.get(i);
        }
        return jdbc.update("UPDATE review_cycles SET status = ?, modified_by = ?, modified_date = now()"
                + " WHERE cycle_id = ? AND status IN (" + placeholders + ")", args);
    }

    public List<RatingCount> ratingDistribution(long cycleId, Long deptId) {
        return jdbc.query("""
                SELECT pr.rating_label, count(*) AS cnt
                FROM performance_reviews pr JOIN employees e ON e.emp_id = pr.emp_id
                WHERE pr.cycle_id = ? AND pr.overall_rating IS NOT NULL
                  AND (CAST(? AS BIGINT) IS NULL OR e.dept_id = ?)
                GROUP BY pr.rating_label
                ORDER BY min(pr.overall_rating) DESC
                """, (rs, i) -> new RatingCount(rs.getString(1), rs.getInt(2)), cycleId, deptId, deptId);
    }

    // ----------------------------------------------------------------- reviews

    public Optional<PerformanceReview> findReview(long reviewId) {
        return jdbc.query(REVIEW_SELECT + " WHERE pr.review_id = ?", PerformanceRepository::mapReview, reviewId)
                .stream().findFirst();
    }

    public Optional<PerformanceReview> lockReview(long reviewId) {
        jdbc.query("SELECT review_id FROM performance_reviews WHERE review_id = ? FOR UPDATE", rs -> { }, reviewId);
        return findReview(reviewId);
    }

    public List<PerformanceReview> reviewsByEmployee(long empId, Long cycleId) {
        return reviewsWhere("pr.emp_id = ?", empId, cycleId);
    }

    public List<PerformanceReview> reviewsByReviewer(long reviewerEmpId, Long cycleId) {
        return reviewsWhere("pr.reviewer_emp_id = ?", reviewerEmpId, cycleId);
    }

    private List<PerformanceReview> reviewsWhere(String condition, long empId, Long cycleId) {
        if (cycleId == null) {
            return jdbc.query(REVIEW_SELECT + " WHERE " + condition + " ORDER BY pr.cycle_id DESC, e.last_name",
                    PerformanceRepository::mapReview, empId);
        }
        return jdbc.query(REVIEW_SELECT + " WHERE " + condition + " AND pr.cycle_id = ?"
                + " ORDER BY e.last_name, e.first_name", PerformanceRepository::mapReview, empId, cycleId);
    }

    public boolean reviewExists(long cycleId, long empId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM performance_reviews WHERE cycle_id = ? AND emp_id = ?",
                Integer.class, cycleId, empId);
        return n != null && n > 0;
    }

    /** Inserts a NOT_STARTED ANNUAL review; returns its id, or empty if (cycle, emp) already has one. */
    public Optional<Long> insertReview(long cycleId, long empId, long reviewerEmpId, String user) {
        List<Long> ids = jdbc.query("""
                INSERT INTO performance_reviews (cycle_id, emp_id, reviewer_emp_id, review_type, status, created_by)
                VALUES (?, ?, ?, 'ANNUAL', 'NOT_STARTED', ?)
                ON CONFLICT (cycle_id, emp_id) DO NOTHING
                RETURNING review_id
                """, (rs, i) -> rs.getLong(1), cycleId, empId, reviewerEmpId, user);
        return ids.stream().findFirst();
    }

    public void saveSelfAssessment(long reviewId, String text, BigDecimal selfRating, String user) {
        jdbc.update("""
                UPDATE performance_reviews SET self_assessment = ?, self_rating = ?, status = 'MANAGER_REVIEW',
                       modified_by = ?, modified_date = now()
                WHERE review_id = ?
                """, text, selfRating, user, reviewId);
    }

    public void saveManagerReview(long reviewId, BigDecimal rating, String label, String comments, String strengths,
                                  String improvements, String user) {
        jdbc.update("""
                UPDATE performance_reviews SET overall_rating = ?, rating_label = ?, manager_assessment = ?,
                       strengths = ?, areas_for_improvement = ?, status = 'COMPLETED',
                       modified_by = ?, modified_date = now()
                WHERE review_id = ?
                """, rating, label, comments, strengths, improvements, user, reviewId);
    }

    public void acknowledge(long reviewId, String user) {
        jdbc.update("""
                UPDATE performance_reviews SET status = 'ACKNOWLEDGED', employee_ack_date = now(),
                       modified_by = ?, modified_date = now()
                WHERE review_id = ?
                """, user, reviewId);
    }

    // ------------------------------------------------------------------- goals

    public List<PerformanceGoal> goals(long reviewId) {
        return jdbc.query(GOAL_SELECT + " WHERE review_id = ? ORDER BY goal_id", PerformanceRepository::mapGoal,
                reviewId);
    }

    /** Goal plus its owning review id. */
    public record GoalRow(PerformanceGoal goal, long reviewId) { }

    public Optional<GoalRow> lockGoal(long goalId) {
        return jdbc.query(GOAL_SELECT + " WHERE goal_id = ? FOR UPDATE",
                (rs, i) -> new GoalRow(mapGoal(rs, i), rs.getLong("review_id")), goalId).stream().findFirst();
    }

    public long insertGoal(long reviewId, long empId, PerformanceGoal g, String user) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO performance_goals (review_id, emp_id, goal_title, goal_description, goal_category,
                                                   weight_pct, target_date, status, progress_pct, created_by)
                    VALUES (?, ?, ?, ?, 'BUSINESS', ?, ?, 'NOT_STARTED', 0, ?)
                    """, new String[] {"goal_id"});
            ps.setLong(1, reviewId);
            ps.setLong(2, empId);
            ps.setString(3, g.goalTitle().trim());
            ps.setString(4, g.goalDescription());
            ps.setBigDecimal(5, g.weightPct() == null ? BigDecimal.ZERO : g.weightPct());
            ps.setObject(6, g.targetDate());
            ps.setString(7, user);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public PerformanceGoal findGoal(long goalId) {
        return jdbc.queryForObject(GOAL_SELECT + " WHERE goal_id = ?", PerformanceRepository::mapGoal, goalId);
    }

    public void updateGoalProgress(long goalId, BigDecimal progress, String status, String user) {
        jdbc.update("""
                UPDATE performance_goals SET progress_pct = ?, status = ?, modified_by = ?, modified_date = now()
                WHERE goal_id = ?
                """, progress, status, user, goalId);
    }

    // ----------------------------------------------------------------- mappers

    private static ReviewCycle mapCycle(ResultSet rs, int row) throws SQLException {
        return new ReviewCycle(rs.getLong("cycle_id"), rs.getString("cycle_name"), rs.getInt("cycle_year"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getObject("self_review_due", LocalDate.class), rs.getObject("manager_review_due", LocalDate.class),
                rs.getString("status"));
    }

    private static PerformanceReview mapReview(ResultSet rs, int row) throws SQLException {
        return new PerformanceReview(rs.getLong("review_id"), rs.getLong("cycle_id"), rs.getLong("emp_id"),
                rs.getString("employee_name"), rs.getLong("reviewer_emp_id"), rs.getString("reviewer_name"),
                rs.getString("status"), rs.getString("self_assessment"), rs.getBigDecimal("self_rating"),
                rs.getString("manager_assessment"), rs.getBigDecimal("overall_rating"), rs.getString("rating_label"),
                null);
    }

    private static PerformanceGoal mapGoal(ResultSet rs, int row) throws SQLException {
        return new PerformanceGoal(rs.getLong("goal_id"), rs.getString("goal_title"), rs.getString("goal_description"),
                rs.getBigDecimal("weight_pct"), rs.getObject("target_date", LocalDate.class),
                rs.getBigDecimal("progress_pct"), rs.getString("status"));
    }
}
