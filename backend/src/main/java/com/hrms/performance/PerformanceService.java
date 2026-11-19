package com.hrms.performance;

import com.hrms.performance.PerformanceModels.CreateReviewRequest;
import com.hrms.performance.PerformanceModels.GoalProgressRequest;
import com.hrms.performance.PerformanceModels.ManagerReviewRequest;
import com.hrms.performance.PerformanceModels.PerformanceGoal;
import com.hrms.performance.PerformanceModels.PerformanceReview;
import com.hrms.performance.PerformanceModels.RatingCount;
import com.hrms.performance.PerformanceModels.ReviewCycle;
import com.hrms.performance.PerformanceModels.SelfAssessmentRequest;
import com.hrms.performance.PerformanceRepository.GoalRow;
import com.hrms.platform.audit.AuditService;
import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.employee.EmployeeSummary;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.notification.NotificationService;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performance workflow ported from PKG_PERFORMANCE (inventory §4.10) with the §9 fixes: review
 * generation is idempotent on (cycle, employee), the manager review requires MANAGER_REVIEW,
 * acknowledging a review in the wrong status is a 409 instead of a silent no-op, and every
 * action is authorised server-side (D19, D21).
 */
@Service
public class PerformanceService {

    static final BigDecimal MIN_RATING = BigDecimal.ONE;
    static final BigDecimal MAX_RATING = BigDecimal.valueOf(5);
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final Set<String> CYCLE_STATUSES = Set.of("DRAFT", "OPEN", "IN_PROGRESS", "CALIBRATION", "CLOSED");
    static final Set<String> GOAL_STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "DEFERRED");
    private static final String REVIEWS = "PERFORMANCE_REVIEWS";

    private final PerformanceRepository repo;
    private final EmployeeLookup employees;
    private final NotificationService notifications;
    private final AuditService audit;
    private final PermissionService permissions;

    public PerformanceService(PerformanceRepository repo, EmployeeLookup employees, NotificationService notifications,
                              AuditService audit, PermissionService permissions) {
        this.repo = repo;
        this.employees = employees;
        this.notifications = notifications;
        this.audit = audit;
        this.permissions = permissions;
    }

    /** Legacy submit_manager_review CASE. */
    static String ratingLabel(BigDecimal rating) {
        double r = rating.doubleValue();
        if (r >= 4.5) {
            return "Exceptional";
        }
        if (r >= 3.5) {
            return "Exceeds Expectations";
        }
        if (r >= 2.5) {
            return "Meets Expectations";
        }
        if (r >= 1.5) {
            return "Needs Improvement";
        }
        return "Unsatisfactory";
    }

    // ------------------------------------------------------------------ cycles

    public List<ReviewCycle> cycles(String status) {
        if (status == null || status.isBlank()) {
            return repo.cycles(null);
        }
        String s = status.toUpperCase();
        if (!CYCLE_STATUSES.contains(s)) {
            throw new ApiException(ErrorCode.VALIDATION, "Unknown cycle status: " + status);
        }
        return repo.cycles(s);
    }

    @Transactional
    public ReviewCycle createCycle(CurrentUser user, ReviewCycle c) {
        permissions.require(user, Module.PERFORMANCE, Action.CREATE);
        if (c.startDate().isAfter(c.endDate())) {
            throw new ApiException(ErrorCode.VALIDATION, "Cycle start date must be on or before the end date");
        }
        if (c.selfReviewDeadline() != null && c.managerReviewDeadline() != null
                && c.selfReviewDeadline().isAfter(c.managerReviewDeadline())) {
            throw new ApiException(ErrorCode.VALIDATION,
                    "Self-review deadline must be on or before the manager-review deadline");
        }
        int year = c.cycleYear() != null ? c.cycleYear() : c.startDate().getYear();
        long id = repo.insertCycle(c, year, user.username());
        ReviewCycle created = cycle(id);
        audit.record("REVIEW_CYCLES", id, AuditService.Action.INSERT, null, created, user.username());
        return created;
    }

    @Transactional
    public ReviewCycle openCycle(CurrentUser user, long cycleId) {
        permissions.require(user, Module.PERFORMANCE, Action.EDIT);
        ReviewCycle before = cycle(cycleId);
        if (repo.setCycleStatus(cycleId, "OPEN", List.of("DRAFT"), user.username()) == 0) {
            throw new ApiException(ErrorCode.PERFORMANCE_CYCLE_NOT_DRAFT,
                    "Cannot open cycle - must be in DRAFT status (is " + before.status() + ")");
        }
        audit.record("REVIEW_CYCLES", cycleId, AuditService.Action.UPDATE, Map.of("status", before.status()),
                Map.of("status", "OPEN"), user.username());
        return cycle(cycleId);
    }

    @Transactional
    public ReviewCycle closeCycle(CurrentUser user, long cycleId) {
        permissions.require(user, Module.PERFORMANCE, Action.EDIT);
        ReviewCycle before = cycle(cycleId);
        if (repo.setCycleStatus(cycleId, "CLOSED", List.of("DRAFT", "OPEN", "IN_PROGRESS", "CALIBRATION"),
                user.username()) > 0) {
            audit.record("REVIEW_CYCLES", cycleId, AuditService.Action.UPDATE, Map.of("status", before.status()),
                    Map.of("status", "CLOSED"), user.username());
        }
        return cycle(cycleId);
    }

    /** One review per ACTIVE employee with a manager; re-running only adds the missing ones. */
    @Transactional
    public int generateReviews(CurrentUser user, long cycleId) {
        permissions.require(user, Module.PERFORMANCE, Action.EDIT);
        requireNotClosed(cycle(cycleId));
        int created = 0;
        for (EmployeeSummary e : employees.activeEmployees()) {
            if (e.managerEmpId() != null && insertReview(cycleId, e.empId(), e.managerEmpId(), user)) {
                created++;
            }
        }
        return created;
    }

    public List<RatingCount> ratingDistribution(CurrentUser user, long cycleId, Long deptId) {
        permissions.require(user, Module.PERFORMANCE, Action.VIEW);
        cycle(cycleId);
        return repo.ratingDistribution(cycleId, deptId);
    }

    // ----------------------------------------------------------------- reviews

    public List<PerformanceReview> reviews(CurrentUser user, String scope, Long cycleId) {
        String s = scope == null || scope.isBlank() ? "mine" : scope;
        return switch (s) {
            case "mine" -> repo.reviewsByEmployee(user.empId(), cycleId);
            case "team" -> repo.reviewsByReviewer(user.empId(), cycleId);
            default -> throw new ApiException(ErrorCode.VALIDATION, "scope must be 'mine' or 'team'");
        };
    }

    @Transactional
    public PerformanceReview createReview(CurrentUser user, CreateReviewRequest req) {
        ReviewCycle c = cycle(req.cycleId());
        requireNotClosed(c);
        EmployeeSummary emp = employees.findById(req.empId())
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found: " + req.empId()));
        if (!emp.isActiveEmployee()) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ACTIVE, "Employee is not active: " + req.empId());
        }
        boolean isManager = emp.managerEmpId() != null && emp.managerEmpId() == user.empId();
        if (!isManager && !permissions.hasPermission(user, Module.PERFORMANCE, Action.CREATE)) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE,
                    "Only the employee's manager or an HR administrator can create a review");
        }
        Long reviewerId = req.reviewerEmpId() != null ? req.reviewerEmpId()
                : isManager ? Long.valueOf(user.empId()) : emp.managerEmpId();
        if (reviewerId == null) {
            throw new ApiException(ErrorCode.VALIDATION, "reviewerEmpId is required for an employee without a manager");
        }
        if (reviewerId == emp.empId()) {
            throw new ApiException(ErrorCode.VALIDATION, "An employee cannot review themselves");
        }
        if (employees.findById(reviewerId).filter(EmployeeSummary::isActiveEmployee).isEmpty()) {
            throw new ApiException(ErrorCode.EMPLOYEE_INVALID_MANAGER, "Reviewer not found or not active: " + reviewerId);
        }
        if (repo.reviewExists(c.cycleId(), emp.empId()) || !insertReview(c.cycleId(), emp.empId(), reviewerId, user)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Employee " + emp.empId() + " already has a review in cycle " + c.cycleId());
        }
        return repo.reviewsByEmployee(emp.empId(), c.cycleId()).get(0).withGoals(List.of());
    }

    private boolean insertReview(long cycleId, long empId, long reviewerId, CurrentUser user) {
        return repo.insertReview(cycleId, empId, reviewerId, user.username()).map(id -> {
            audit.record(REVIEWS, id, AuditService.Action.INSERT, null,
                    Map.of("cycleId", cycleId, "empId", empId, "reviewerEmpId", reviewerId, "status", "NOT_STARTED"),
                    user.username());
            notifications.queue(empId, "Performance Review Initiated",
                    "Your annual performance review has been initiated. Please complete your self-assessment.",
                    REVIEWS, id, user.username());
            return true;
        }).orElse(false);
    }

    public PerformanceReview get(CurrentUser user, long reviewId) {
        PerformanceReview r = load(reviewId);
        requireCanView(user, r);
        return r.withGoals(repo.goals(reviewId));
    }

    @Transactional
    public PerformanceReview submitSelfAssessment(CurrentUser user, long reviewId, SelfAssessmentRequest req) {
        PerformanceReview r = lock(reviewId);
        if (r.empId() != user.empId()) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE,
                    "Only the reviewed employee can submit the self-assessment");
        }
        if (!"NOT_STARTED".equals(r.status()) && !"SELF_REVIEW".equals(r.status())) {
            throw new ApiException(ErrorCode.PERFORMANCE_WRONG_STATUS,
                    "Review not in correct status for self-assessment (is " + r.status() + ")");
        }
        BigDecimal selfRating = req.selfRating() == null ? null : rating(req.selfRating());
        repo.saveSelfAssessment(reviewId, req.selfAssessment(), selfRating, user.username());
        audit.record(REVIEWS, reviewId, AuditService.Action.UPDATE, Map.of("status", r.status()),
                Map.of("status", "MANAGER_REVIEW"), user.username());
        notifications.queue(r.reviewerEmpId(), "Self-Assessment Submitted - Ready for Manager Review",
                "An employee has completed their self-assessment. Please proceed with the manager review.",
                REVIEWS, reviewId, user.username());
        return get(user, reviewId);
    }

    @Transactional
    public PerformanceReview submitManagerReview(CurrentUser user, long reviewId, ManagerReviewRequest req) {
        PerformanceReview r = lock(reviewId);
        if (r.reviewerEmpId() != user.empId()) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE,
                    "Only the assigned reviewer can submit the manager review");
        }
        if (!"MANAGER_REVIEW".equals(r.status())) {
            throw new ApiException(ErrorCode.PERFORMANCE_WRONG_STATUS,
                    "Review must be in MANAGER_REVIEW status (is " + r.status() + ")");
        }
        BigDecimal overall = rating(req.overallRating());
        String label = ratingLabel(overall);
        repo.saveManagerReview(reviewId, overall, label, req.managerComments(), req.strengths(), req.improvements(),
                user.username());
        audit.record(REVIEWS, reviewId, AuditService.Action.UPDATE, Map.of("status", r.status()),
                Map.of("status", "COMPLETED", "overallRating", overall, "ratingLabel", label), user.username());
        notifications.queue(r.empId(), "Performance Review Completed",
                "Your manager has completed your performance review. Please review and acknowledge.",
                REVIEWS, reviewId, user.username());
        return get(user, reviewId);
    }

    @Transactional
    public PerformanceReview acknowledge(CurrentUser user, long reviewId) {
        PerformanceReview r = lock(reviewId);
        if (r.empId() != user.empId()) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE,
                    "Only the reviewed employee can acknowledge the review");
        }
        if (!"COMPLETED".equals(r.status())) {
            throw new ApiException(ErrorCode.PERFORMANCE_WRONG_STATUS,
                    "Only a COMPLETED review can be acknowledged (is " + r.status() + ")");
        }
        repo.acknowledge(reviewId, user.username());
        audit.record(REVIEWS, reviewId, AuditService.Action.UPDATE, Map.of("status", r.status()),
                Map.of("status", "ACKNOWLEDGED"), user.username());
        return get(user, reviewId);
    }

    // ------------------------------------------------------------------- goals

    public List<PerformanceGoal> goals(CurrentUser user, long reviewId) {
        requireCanView(user, load(reviewId));
        return repo.goals(reviewId);
    }

    @Transactional
    public PerformanceGoal addGoal(CurrentUser user, long reviewId, PerformanceGoal goal) {
        PerformanceReview r = lock(reviewId);
        requireCanEditGoals(user, r);
        if ("ACKNOWLEDGED".equals(r.status())) {
            throw new ApiException(ErrorCode.PERFORMANCE_WRONG_STATUS, "Goals cannot be added to an acknowledged review");
        }
        long id = repo.insertGoal(reviewId, r.empId(), goal, user.username());
        PerformanceGoal created = repo.findGoal(id);
        audit.record("PERFORMANCE_GOALS", id, AuditService.Action.INSERT, null, created, user.username());
        return created;
    }

    @Transactional
    public PerformanceGoal updateGoalProgress(CurrentUser user, long goalId, GoalProgressRequest req) {
        GoalRow row = repo.lockGoal(goalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Goal not found: " + goalId));
        requireCanEditGoals(user, load(row.reviewId()));
        String status;
        if (req.status() != null && !req.status().isBlank()) {
            status = req.status().toUpperCase();
            if (!GOAL_STATUSES.contains(status)) {
                throw new ApiException(ErrorCode.VALIDATION, "Unknown goal status: " + req.status());
            }
        } else if (req.progressPct().compareTo(HUNDRED) >= 0) {
            status = "COMPLETED";
        } else if (req.progressPct().signum() > 0) {
            status = "IN_PROGRESS";
        } else {
            status = row.goal().status();
        }
        repo.updateGoalProgress(goalId, req.progressPct(), status, user.username());
        PerformanceGoal updated = repo.findGoal(goalId);
        audit.record("PERFORMANCE_GOALS", goalId, AuditService.Action.UPDATE,
                Map.of("progressPct", row.goal().progressPct(), "status", row.goal().status()),
                Map.of("progressPct", updated.progressPct(), "status", updated.status()), user.username());
        return updated;
    }

    // ----------------------------------------------------------------- helpers

    private ReviewCycle cycle(long cycleId) {
        return repo.findCycle(cycleId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Review cycle not found: " + cycleId));
    }

    private static void requireNotClosed(ReviewCycle c) {
        if ("CLOSED".equals(c.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Review cycle " + c.cycleId() + " is CLOSED");
        }
    }

    private PerformanceReview load(long reviewId) {
        return repo.findReview(reviewId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Review not found: " + reviewId));
    }

    private PerformanceReview lock(long reviewId) {
        return repo.lockReview(reviewId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Review not found: " + reviewId));
    }

    private static BigDecimal rating(BigDecimal value) {
        if (value.compareTo(MIN_RATING) < 0 || value.compareTo(MAX_RATING) > 0) {
            throw new ApiException(ErrorCode.PERFORMANCE_INVALID_RATING, "Rating must be between 1.0 and 5.0");
        }
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    private void requireCanView(CurrentUser user, PerformanceReview r) {
        boolean allowed = r.empId() == user.empId() || r.reviewerEmpId() == user.empId()
                || employees.isManagerOf(user.empId(), r.empId())
                || permissions.hasPermission(user, Module.PERFORMANCE, Action.VIEW);
        if (!allowed) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE, "Not allowed to view this review");
        }
    }

    private void requireCanEditGoals(CurrentUser user, PerformanceReview r) {
        boolean allowed = r.empId() == user.empId() || r.reviewerEmpId() == user.empId()
                || permissions.hasPermission(user, Module.PERFORMANCE, Action.EDIT);
        if (!allowed) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE,
                    "Only the employee or the reviewer can change this review's goals");
        }
    }
}
