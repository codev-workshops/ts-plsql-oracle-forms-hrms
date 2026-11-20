package com.hrms.performance;

import com.hrms.auth.Action;
import com.hrms.auth.HrmsPrincipal;
import com.hrms.auth.Module;
import com.hrms.common.ApiException;
import com.hrms.common.AuditWriter;
import com.hrms.common.ErrorCode;
import com.hrms.notification.NotificationService;
import com.hrms.performance.PerformanceModels.Acknowledgement;
import com.hrms.performance.PerformanceModels.AddGoal;
import com.hrms.performance.PerformanceModels.CreateReviewCycle;
import com.hrms.performance.PerformanceModels.CycleStatus;
import com.hrms.performance.PerformanceModels.GenerateReviewsResult;
import com.hrms.performance.PerformanceModels.GoalCategory;
import com.hrms.performance.PerformanceModels.GoalProgress;
import com.hrms.performance.PerformanceModels.GoalStatus;
import com.hrms.performance.PerformanceModels.ManagerReview;
import com.hrms.performance.PerformanceModels.PerformanceGoal;
import com.hrms.performance.PerformanceModels.PerformanceReview;
import com.hrms.performance.PerformanceModels.RatingBucket;
import com.hrms.performance.PerformanceModels.ReviewCycle;
import com.hrms.performance.PerformanceModels.ReviewRole;
import com.hrms.performance.PerformanceModels.ReviewStatus;
import com.hrms.performance.PerformanceModels.SelfAssessment;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces PKG_PERFORMANCE. Cycle lifecycle DRAFT → OPEN → CLOSED and the review workflow are enforced
 * here (DEF-21 fixed per OQ-04); the HRMS_PERFORMANCE block-level DML policy is re-homed as "commands
 * only" (FRM-071..075) and the OQ-09 row scope: reviews and goals are written by the reviewed employee
 * or the assigned reviewer, and read by them or PERFORMANCE:VIEW holders (others get 404).
 */
@Service
public class PerformanceService {

    static final String REVIEW_CYCLES = "REVIEW_CYCLES";
    static final String PERFORMANCE_REVIEWS = "PERFORMANCE_REVIEWS";
    static final String PERFORMANCE_GOALS = "PERFORMANCE_GOALS";

    private static final Set<CycleStatus> CLOSABLE =
            EnumSet.of(CycleStatus.OPEN, CycleStatus.IN_PROGRESS, CycleStatus.CALIBRATION);
    private static final Set<ReviewStatus> SELF_ASSESSABLE = EnumSet.of(ReviewStatus.NOT_STARTED, ReviewStatus.SELF_REVIEW);
    private static final Set<ReviewStatus> MANAGER_REVIEWABLE =
            EnumSet.of(ReviewStatus.MANAGER_REVIEW, ReviewStatus.MEETING_SCHEDULED);
    private static final BigDecimal MIN_RATING = new BigDecimal("1.0");
    private static final BigDecimal MAX_RATING = new BigDecimal("5.0");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final String SELECT_CYCLE = """
            SELECT cycle_id, cycle_name, cycle_year, start_date, end_date, self_review_due, manager_review_due,
                   calibration_due, status, created_by, created_date, modified_by, modified_date
            FROM hrms.review_cycles
            """;

    private static final String SELECT_REVIEW = """
            SELECT r.review_id, r.cycle_id, c.cycle_name, r.emp_id, e.first_name || ' ' || e.last_name AS employee_name,
                   j.job_title, d.dept_name, r.reviewer_emp_id, m.first_name || ' ' || m.last_name AS reviewer_name,
                   r.review_type, r.status, r.overall_rating, r.rating_label, r.self_assessment, r.manager_assessment,
                   r.strengths, r.areas_for_improvement, r.development_plan, r.employee_comments,
                   r.employee_ack_date, r.created_date, r.modified_date
            FROM hrms.performance_reviews r
            JOIN hrms.review_cycles c ON c.cycle_id = r.cycle_id
            JOIN hrms.employees e ON e.emp_id = r.emp_id
            JOIN hrms.employees m ON m.emp_id = r.reviewer_emp_id
            LEFT JOIN hrms.job_titles j ON j.job_id = e.job_id
            LEFT JOIN hrms.departments d ON d.dept_id = e.dept_id
            """;

    private static final String SELECT_GOAL = """
            SELECT goal_id, review_id, emp_id, goal_title, goal_description, goal_category, weight_pct, target_date,
                   status, progress_pct, comments, created_by, created_date, modified_by, modified_date
            FROM hrms.performance_goals
            """;

    private final JdbcClient jdbc;
    private final AuditWriter audit;
    private final NotificationService notifications;
    private final Clock clock;

    public PerformanceService(JdbcClient jdbc, AuditWriter audit, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** The authenticated caller and the client address recorded in the audit trail. */
    public record Caller(HrmsPrincipal principal, String ipAddress) {
    }

    // ---- cycles (FRM-070, FRM-071, PKG-F01) -------------------------------------------------------------

    /** FRM-070: the form's DEFAULT_WHERE (OPEN and DRAFT, newest year first) unless closed cycles are asked for. */
    @Transactional(readOnly = true)
    public List<ReviewCycle> cycles(boolean includeClosed) {
        return jdbc.sql(SELECT_CYCLE + (includeClosed ? "" : " WHERE status IN ('OPEN', 'DRAFT')")
                + " ORDER BY cycle_year DESC, cycle_id DESC").query(PerformanceService::cycle).list();
    }

    @Transactional(readOnly = true)
    public ReviewCycle cycle(long cycleId) {
        return findCycle(cycleId).orElseThrow(PerformanceService::cycleNotFound);
    }

    /** create_review_cycle: new cycles start in DRAFT. */
    @Transactional
    public ReviewCycle createCycle(Caller caller, CreateReviewCycle body) {
        if (body.endDate().isBefore(body.startDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "endDate must not be before startDate");
        }
        String actor = caller.principal().actor();
        long id = jdbc.sql("""
                INSERT INTO hrms.review_cycles (cycle_name, cycle_year, start_date, end_date, self_review_due,
                    manager_review_due, calibration_due, status, created_by, created_date)
                VALUES (:name, :year, :start, :end, :selfDue, :managerDue, :calibrationDue, 'DRAFT', :actor, :now)
                RETURNING cycle_id
                """).param("name", body.cycleName()).param("year", body.cycleYear()).param("start", body.startDate())
                .param("end", body.endDate()).param("selfDue", body.selfReviewDue())
                .param("managerDue", body.managerReviewDue()).param("calibrationDue", body.calibrationDue())
                .param("actor", actor).param("now", now()).query(Long.class).single();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("cycleName", body.cycleName());
        values.put("cycleYear", body.cycleYear());
        values.put("startDate", body.startDate().toString());
        values.put("endDate", body.endDate().toString());
        values.put("status", CycleStatus.DRAFT.name());
        write(caller, REVIEW_CYCLES, id, "INSERT", null, values);
        return cycle(id);
    }

    /** open_review_cycle: DRAFT → OPEN, else -20401. */
    @Transactional
    public ReviewCycle openCycle(Caller caller, long cycleId) {
        return transitionCycle(caller, cycleId, EnumSet.of(CycleStatus.DRAFT), CycleStatus.OPEN,
                "Cycle not found or not in DRAFT status");
    }

    /** close_review_cycle: only an open cycle closes (DEF-21: legacy closed from any status). */
    @Transactional
    public ReviewCycle closeCycle(Caller caller, long cycleId) {
        return transitionCycle(caller, cycleId, CLOSABLE, CycleStatus.CLOSED, "Only an OPEN cycle can be closed");
    }

    private ReviewCycle transitionCycle(Caller caller, long cycleId, Set<CycleStatus> from, CycleStatus to,
            String message) {
        CycleStatus current = jdbc.sql("SELECT status FROM hrms.review_cycles WHERE cycle_id = :id FOR UPDATE")
                .param("id", cycleId).query(String.class).optional().map(CycleStatus::valueOf)
                .orElseThrow(PerformanceService::cycleNotFound);
        if (!from.contains(current)) {
            throw new ApiException(ErrorCode.INVALID_CYCLE_STATUS, message + " (current status " + current + ")");
        }
        jdbc.sql("UPDATE hrms.review_cycles SET status = :status, modified_by = :actor, modified_date = :now "
                + "WHERE cycle_id = :id").param("status", to.name()).param("actor", caller.principal().actor())
                .param("now", now()).param("id", cycleId).update();
        write(caller, REVIEW_CYCLES, cycleId, "STATUS_CHANGE", Map.of("status", current.name()),
                Map.of("status", to.name()));
        return cycle(cycleId);
    }

    // ---- reviews (FRM-072, FRM-073, FRM-076, FRM-077, PKG-F02, PKG-F03) ---------------------------------

    /**
     * generate_reviews_for_cycle: one ANNUAL review per ACTIVE employee that has a manager, reviewed by that
     * manager. Existing reviews are skipped (the unique key the legacy DUP_VAL_ON_INDEX handler assumed).
     */
    @Transactional
    public GenerateReviewsResult generateReviews(Caller caller, long cycleId) {
        jdbc.sql("SELECT cycle_id FROM hrms.review_cycles WHERE cycle_id = :id FOR UPDATE").param("id", cycleId)
                .query(Long.class).optional().orElseThrow(PerformanceService::cycleNotFound);
        List<long[]> employees = jdbc.sql("""
                SELECT emp_id, manager_emp_id FROM hrms.employees
                WHERE employment_status = 'ACTIVE' AND manager_emp_id IS NOT NULL ORDER BY emp_id
                """).query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}).list();
        int created = 0;
        for (long[] e : employees) {
            if (createReview(caller, cycleId, e[0], e[1]).isPresent()) {
                created++;
            }
        }
        return new GenerateReviewsResult(cycleId, created, employees.size() - created);
    }

    /** create_review: NOT_STARTED, employee notified. Empty when the employee already has the review. */
    private Optional<Long> createReview(Caller caller, long cycleId, long empId, long reviewerEmpId) {
        String actor = caller.principal().actor();
        Optional<Long> id = jdbc.sql("""
                INSERT INTO hrms.performance_reviews (cycle_id, emp_id, reviewer_emp_id, review_type, status,
                    created_by, created_date)
                VALUES (:cycleId, :empId, :reviewerId, 'ANNUAL', 'NOT_STARTED', :actor, :now)
                ON CONFLICT (cycle_id, emp_id, review_type) DO NOTHING
                RETURNING review_id
                """).param("cycleId", cycleId).param("empId", empId).param("reviewerId", reviewerEmpId)
                .param("actor", actor).param("now", now()).query(Long.class).optional();
        id.ifPresent(reviewId -> {
            write(caller, PERFORMANCE_REVIEWS, reviewId, "INSERT", null, Map.of("cycleId", cycleId, "empId", empId,
                    "reviewerEmpId", reviewerEmpId, "status", ReviewStatus.NOT_STARTED.name()));
            notifications.queueEmail(empId, "Performance Review Initiated",
                    "Your annual performance review has been initiated. Please complete your self-assessment.",
                    PERFORMANCE_REVIEWS, reviewId, actor);
        });
        return id;
    }

    /** All reviews of a cycle (HR view, PERFORMANCE:VIEW). */
    @Transactional(readOnly = true)
    public List<PerformanceReview> cycleReviews(long cycleId, ReviewStatus status) {
        cycle(cycleId);
        return jdbc.sql(SELECT_REVIEW + " WHERE r.cycle_id = :cycleId AND (CAST(:status AS varchar) IS NULL "
                + "OR r.status = :status) ORDER BY e.last_name, e.first_name, r.review_id")
                .param("cycleId", cycleId).param("status", status == null ? null : status.name())
                .query(PerformanceService::review).list();
    }

    /** The caller's own reviews, or (REVIEWER) the reviews assigned to the caller: get_team_reviews. */
    @Transactional(readOnly = true)
    public List<PerformanceReview> reviewsOf(long empId, ReviewRole role, Long cycleId) {
        String column = role == ReviewRole.REVIEWER ? "r.reviewer_emp_id" : "r.emp_id";
        String order = role == ReviewRole.REVIEWER ? "e.last_name, e.first_name, r.review_id"
                : "c.cycle_year DESC, r.review_id DESC";
        return jdbc.sql(SELECT_REVIEW + " WHERE " + column + " = :empId AND (CAST(:cycleId AS bigint) IS NULL "
                + "OR r.cycle_id = :cycleId) ORDER BY " + order)
                .param("empId", empId).param("cycleId", cycleId).query(PerformanceService::review).list();
    }

    @Transactional(readOnly = true)
    public PerformanceReview review(HrmsPrincipal p, long reviewId) {
        PerformanceReview r = findReview(reviewId);
        if (p.empId() != r.empId() && p.empId() != r.reviewerEmpId() && !p.has(Module.PERFORMANCE, Action.VIEW)) {
            throw reviewNotFound();
        }
        return r;
    }

    /** get_rating_distribution: rated reviews of the cycle per label, best label first. */
    @Transactional(readOnly = true)
    public List<RatingBucket> ratingDistribution(long cycleId, Long deptId) {
        cycle(cycleId);
        return jdbc.sql("""
                SELECT r.rating_label, COUNT(*) AS review_count,
                       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS percentage
                FROM hrms.performance_reviews r JOIN hrms.employees e ON e.emp_id = r.emp_id
                WHERE r.cycle_id = :cycleId AND r.overall_rating IS NOT NULL
                AND (CAST(:deptId AS bigint) IS NULL OR e.dept_id = :deptId)
                GROUP BY r.rating_label ORDER BY MIN(r.overall_rating) DESC
                """).param("cycleId", cycleId).param("deptId", deptId)
                .query((rs, i) -> new RatingBucket(rs.getString(1), rs.getInt(2), rs.getBigDecimal(3))).list();
    }

    /** submit_self_assessment: the reviewed employee, NOT_STARTED/SELF_REVIEW → MANAGER_REVIEW (-20402). */
    @Transactional
    public PerformanceReview submitSelfAssessment(Caller caller, long reviewId, SelfAssessment body) {
        LockedReview r = lockReview(reviewId);
        r.require(caller.principal(), EnumSet.of(ReviewRole.EMPLOYEE), "submit the self-assessment");
        r.requireStatus(SELF_ASSESSABLE, "Review not found or not in correct status");
        String actor = caller.principal().actor();
        jdbc.sql("UPDATE hrms.performance_reviews SET self_assessment = :text, status = 'MANAGER_REVIEW', "
                + "modified_by = :actor, modified_date = :now WHERE review_id = :id")
                .param("text", body.selfAssessment()).param("actor", actor).param("now", now()).param("id", reviewId)
                .update();
        statusChange(caller, r, ReviewStatus.MANAGER_REVIEW, Map.of());
        notifications.queueEmail(r.reviewerEmpId(), "Self-Assessment Submitted - Ready for Manager Review",
                "An employee has completed their self-assessment. Please proceed with the manager review.",
                PERFORMANCE_REVIEWS, reviewId, actor);
        return findReview(reviewId);
    }

    /**
     * submit_manager_review: the assigned reviewer, rating 1.0–5.0 with one decimal (-20403), label mapped,
     * MANAGER_REVIEW/MEETING_SCHEDULED → COMPLETED (-20402; DEF-21: legacy completed from any status).
     */
    @Transactional
    public PerformanceReview submitManagerReview(Caller caller, long reviewId, ManagerReview body) {
        LockedReview r = lockReview(reviewId);
        r.require(caller.principal(), EnumSet.of(ReviewRole.REVIEWER), "submit the manager review");
        BigDecimal rating = body.overallRating();
        if (rating.compareTo(MIN_RATING) < 0 || rating.compareTo(MAX_RATING) > 0) {
            throw new ApiException(ErrorCode.INVALID_RATING, "Rating must be between 1.0 and 5.0");
        }
        if (rating.stripTrailingZeros().scale() > 1) {
            throw new ApiException(ErrorCode.INVALID_RATING, "Rating must have at most one decimal place");
        }
        r.requireStatus(MANAGER_REVIEWABLE, "Review is not awaiting the manager review");
        String label = ratingLabel(rating);
        String actor = caller.principal().actor();
        jdbc.sql("""
                UPDATE hrms.performance_reviews SET overall_rating = :rating, rating_label = :label,
                    manager_assessment = :assessment, strengths = :strengths, areas_for_improvement = :areas,
                    development_plan = :plan, status = 'COMPLETED', modified_by = :actor, modified_date = :now
                WHERE review_id = :id
                """).param("rating", rating).param("label", label).param("assessment", body.managerAssessment())
                .param("strengths", body.strengths()).param("areas", body.areasForImprovement())
                .param("plan", body.developmentPlan()).param("actor", actor).param("now", now())
                .param("id", reviewId).update();
        statusChange(caller, r, ReviewStatus.COMPLETED, Map.of("overallRating", rating, "ratingLabel", label));
        notifications.queueEmail(r.empId(), "Performance Review Completed",
                "Your manager has completed your performance review. Please review and acknowledge.",
                PERFORMANCE_REVIEWS, reviewId, actor);
        return findReview(reviewId);
    }

    /** acknowledge_review: the reviewed employee, COMPLETED → ACKNOWLEDGED (DEF-21: legacy silently did nothing). */
    @Transactional
    public PerformanceReview acknowledge(Caller caller, long reviewId, Acknowledgement body) {
        LockedReview r = lockReview(reviewId);
        r.require(caller.principal(), EnumSet.of(ReviewRole.EMPLOYEE), "acknowledge the review");
        r.requireStatus(EnumSet.of(ReviewStatus.COMPLETED), "Only a COMPLETED review can be acknowledged");
        OffsetDateTime now = now();
        jdbc.sql("UPDATE hrms.performance_reviews SET employee_comments = :comments, employee_ack_date = :now, "
                + "status = 'ACKNOWLEDGED', modified_by = :actor, modified_date = :now WHERE review_id = :id")
                .param("comments", body == null ? null : body.comments()).param("now", now)
                .param("actor", caller.principal().actor()).param("id", reviewId).update();
        statusChange(caller, r, ReviewStatus.ACKNOWLEDGED, Map.of());
        return findReview(reviewId);
    }

    /** Rating label of submit_manager_review. */
    static String ratingLabel(BigDecimal rating) {
        if (rating.compareTo(new BigDecimal("4.5")) >= 0) {
            return "Exceptional";
        }
        if (rating.compareTo(new BigDecimal("3.5")) >= 0) {
            return "Exceeds Expectations";
        }
        if (rating.compareTo(new BigDecimal("2.5")) >= 0) {
            return "Meets Expectations";
        }
        if (rating.compareTo(new BigDecimal("1.5")) >= 0) {
            return "Needs Improvement";
        }
        return "Unsatisfactory";
    }

    // ---- goals (FRM-074, FRM-075, PKG-F03) --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PerformanceGoal> goals(HrmsPrincipal p, long reviewId) {
        review(p, reviewId);
        return jdbc.sql(SELECT_GOAL + " WHERE review_id = :id ORDER BY goal_id").param("id", reviewId)
                .query(PerformanceService::goal).list();
    }

    @Transactional(readOnly = true)
    public PerformanceGoal goal(HrmsPrincipal p, long goalId) {
        PerformanceGoal g = findGoal(goalId);
        try {
            review(p, g.reviewId());
        } catch (ApiException e) {
            throw goalNotFound();
        }
        return g;
    }

    /** add_goal: by the employee or the reviewer; the goal belongs to the review's employee. */
    @Transactional
    public PerformanceGoal addGoal(Caller caller, long reviewId, AddGoal body) {
        LockedReview r = lockReview(reviewId);
        r.require(caller.principal(), EnumSet.of(ReviewRole.EMPLOYEE, ReviewRole.REVIEWER), "add goals");
        GoalCategory category = body.goalCategory() == null ? GoalCategory.BUSINESS : body.goalCategory();
        BigDecimal weight = body.weightPct() == null ? BigDecimal.ZERO : body.weightPct();
        long id = jdbc.sql("""
                INSERT INTO hrms.performance_goals (review_id, emp_id, goal_title, goal_description, goal_category,
                    weight_pct, target_date, status, progress_pct, created_by, created_date)
                VALUES (:reviewId, :empId, :title, :description, :category, :weight, :target, 'NOT_STARTED', 0,
                    :actor, :now)
                RETURNING goal_id
                """).param("reviewId", reviewId).param("empId", r.empId()).param("title", body.goalTitle())
                .param("description", body.goalDescription()).param("category", category.name())
                .param("weight", weight).param("target", body.targetDate())
                .param("actor", caller.principal().actor()).param("now", now()).query(Long.class).single();
        write(caller, PERFORMANCE_GOALS, id, "INSERT", null, Map.of("reviewId", reviewId, "empId", r.empId(),
                "goalTitle", body.goalTitle(), "goalCategory", category.name(), "weightPct", weight,
                "status", GoalStatus.NOT_STARTED.name(), "progressPct", 0));
        return findGoal(id);
    }

    /**
     * update_goal_progress: by the employee or the reviewer; 0–100 (DEF-21, validated by the request);
     * status is the given one, else ≥100 COMPLETED, >0 IN_PROGRESS, 0 unchanged; comments kept when absent.
     */
    @Transactional
    public PerformanceGoal updateProgress(Caller caller, long goalId, GoalProgress body) {
        record LockedGoal(long reviewId, GoalStatus status, BigDecimal progress) {
        }
        LockedGoal g = jdbc.sql("SELECT review_id, status, progress_pct FROM hrms.performance_goals "
                + "WHERE goal_id = :id FOR UPDATE").param("id", goalId)
                .query((rs, i) -> new LockedGoal(rs.getLong(1), GoalStatus.valueOf(rs.getString(2)), rs.getBigDecimal(3)))
                .optional().orElseThrow(PerformanceService::goalNotFound);
        LockedReview r = lockReview(g.reviewId());
        try {
            r.require(caller.principal(), EnumSet.of(ReviewRole.EMPLOYEE, ReviewRole.REVIEWER), "update goals");
        } catch (ApiException e) {
            throw e.code() == ErrorCode.NOT_FOUND ? goalNotFound() : e;
        }
        BigDecimal progress = body.progressPct();
        GoalStatus status = body.status() != null ? body.status()
                : progress.compareTo(HUNDRED) >= 0 ? GoalStatus.COMPLETED
                : progress.signum() > 0 ? GoalStatus.IN_PROGRESS : g.status();
        jdbc.sql("UPDATE hrms.performance_goals SET progress_pct = :progress, status = :status, "
                + "comments = COALESCE(:comments, comments), modified_by = :actor, modified_date = :now "
                + "WHERE goal_id = :id").param("progress", progress).param("status", status.name())
                .param("comments", body.comments()).param("actor", caller.principal().actor())
                .param("now", now()).param("id", goalId).update();
        write(caller, PERFORMANCE_GOALS, goalId, "UPDATE",
                Map.of("progressPct", g.progress(), "status", g.status().name()),
                Map.of("progressPct", progress, "status", status.name()));
        return findGoal(goalId);
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private record LockedReview(long reviewId, long empId, long reviewerEmpId, ReviewStatus status) {

        /** OQ-09 row scope: strangers to the review get 404, a party without the right role 403. */
        void require(HrmsPrincipal p, Set<ReviewRole> roles, String what) {
            boolean employee = p.empId() == empId;
            boolean reviewer = p.empId() == reviewerEmpId;
            if (!employee && !reviewer && !p.has(Module.PERFORMANCE, Action.VIEW)) {
                throw reviewNotFound();
            }
            if (!(employee && roles.contains(ReviewRole.EMPLOYEE)) && !(reviewer && roles.contains(ReviewRole.REVIEWER))) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Only the "
                        + (roles.size() == 2 ? "reviewed employee or the reviewer"
                        : roles.contains(ReviewRole.EMPLOYEE) ? "reviewed employee" : "assigned reviewer")
                        + " may " + what);
            }
        }

        void requireStatus(Set<ReviewStatus> allowed, String message) {
            if (!allowed.contains(status)) {
                throw new ApiException(ErrorCode.INVALID_REVIEW_STATUS, message + " (current status " + status + ")");
            }
        }
    }

    private LockedReview lockReview(long reviewId) {
        return jdbc.sql("SELECT review_id, emp_id, reviewer_emp_id, status FROM hrms.performance_reviews "
                + "WHERE review_id = :id FOR UPDATE").param("id", reviewId)
                .query((rs, i) -> new LockedReview(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        ReviewStatus.valueOf(rs.getString(4))))
                .optional().orElseThrow(PerformanceService::reviewNotFound);
    }

    private void statusChange(Caller caller, LockedReview r, ReviewStatus to, Map<String, Object> extra) {
        Map<String, Object> values = new LinkedHashMap<>(extra);
        values.put("status", to.name());
        write(caller, PERFORMANCE_REVIEWS, r.reviewId(), "STATUS_CHANGE", Map.of("status", r.status().name()), values);
    }

    private void write(Caller caller, String table, long id, String action, Map<String, ?> oldValues,
            Map<String, ?> newValues) {
        audit.write(table, id, action, oldValues, newValues, caller.principal().actor(), caller.ipAddress(),
                caller.principal().sessionId());
    }

    private Optional<ReviewCycle> findCycle(long cycleId) {
        return jdbc.sql(SELECT_CYCLE + " WHERE cycle_id = :id").param("id", cycleId)
                .query(PerformanceService::cycle).optional();
    }

    private PerformanceReview findReview(long reviewId) {
        return jdbc.sql(SELECT_REVIEW + " WHERE r.review_id = :id").param("id", reviewId)
                .query(PerformanceService::review).optional().orElseThrow(PerformanceService::reviewNotFound);
    }

    private PerformanceGoal findGoal(long goalId) {
        return jdbc.sql(SELECT_GOAL + " WHERE goal_id = :id").param("id", goalId)
                .query(PerformanceService::goal).optional().orElseThrow(PerformanceService::goalNotFound);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException cycleNotFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Review cycle not found");
    }

    private static ApiException reviewNotFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Performance review not found");
    }

    private static ApiException goalNotFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Performance goal not found");
    }

    private static ReviewCycle cycle(ResultSet rs, int i) throws SQLException {
        return new ReviewCycle(rs.getLong("cycle_id"), rs.getString("cycle_name"), rs.getInt("cycle_year"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getObject("self_review_due", LocalDate.class), rs.getObject("manager_review_due", LocalDate.class),
                rs.getObject("calibration_due", LocalDate.class), CycleStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), rs.getObject("created_date", OffsetDateTime.class),
                rs.getString("modified_by"), rs.getObject("modified_date", OffsetDateTime.class));
    }

    private static PerformanceReview review(ResultSet rs, int i) throws SQLException {
        return new PerformanceReview(rs.getLong("review_id"), rs.getLong("cycle_id"), rs.getString("cycle_name"),
                rs.getLong("emp_id"), rs.getString("employee_name"), rs.getString("job_title"),
                rs.getString("dept_name"), rs.getLong("reviewer_emp_id"), rs.getString("reviewer_name"),
                rs.getString("review_type"), ReviewStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("overall_rating"), rs.getString("rating_label"), rs.getString("self_assessment"),
                rs.getString("manager_assessment"), rs.getString("strengths"), rs.getString("areas_for_improvement"),
                rs.getString("development_plan"), rs.getString("employee_comments"),
                rs.getObject("employee_ack_date", OffsetDateTime.class),
                rs.getObject("created_date", OffsetDateTime.class), rs.getObject("modified_date", OffsetDateTime.class));
    }

    private static PerformanceGoal goal(ResultSet rs, int i) throws SQLException {
        String category = rs.getString("goal_category");
        return new PerformanceGoal(rs.getLong("goal_id"), rs.getLong("review_id"), rs.getLong("emp_id"),
                rs.getString("goal_title"), rs.getString("goal_description"),
                category == null ? null : GoalCategory.valueOf(category), rs.getBigDecimal("weight_pct"),
                rs.getObject("target_date", LocalDate.class), GoalStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("progress_pct"), rs.getString("comments"), rs.getString("created_by"),
                rs.getObject("created_date", OffsetDateTime.class), rs.getString("modified_by"),
                rs.getObject("modified_date", OffsetDateTime.class));
    }
}
