package com.acme.hrms.performance;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.performance.PerformanceDtos.PerformanceGoal;
import com.acme.hrms.performance.PerformanceDtos.PerformanceReview;
import com.acme.hrms.reference.EmployeeLookup;
import com.acme.hrms.validation.dto.performance.GoalProgressRequest;
import com.acme.hrms.validation.dto.performance.GoalRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces {@code PKG_PERFORMANCE.add_goal / update_goal_progress}. Goal rules
 * (COMPONENT_MAPPING.md §6): no new goal once the review is {@code COMPLETED|ACKNOWLEDGED}, no
 * progress once {@code ACKNOWLEDGED} (both {@code -20402}); an explicit {@code status} is stored
 * as-is, otherwise it is derived from progress (100 → COMPLETED, >0 → IN_PROGRESS, 0 → unchanged).
 */
@Service
public class GoalService {

  public static final String TABLE = "PERFORMANCE_GOALS";
  public static final String DEFAULT_CATEGORY = "BUSINESS";

  static final Set<String> NO_NEW_GOALS = Set.of("COMPLETED", "ACKNOWLEDGED");
  static final Set<String> NO_PROGRESS = Set.of("ACKNOWLEDGED");
  static final BigDecimal HUNDRED = new BigDecimal("100");

  private final PerformanceGoalRepository goals;
  private final PerformanceReviewService reviews;
  private final EmployeeLookup employees;
  private final PerformanceAccess access;
  private final AuditService audit;

  public GoalService(
      PerformanceGoalRepository goals,
      PerformanceReviewService reviews,
      EmployeeLookup employees,
      PerformanceAccess access,
      AuditService audit) {
    this.goals = goals;
    this.reviews = reviews;
    this.employees = employees;
    this.access = access;
    this.audit = audit;
  }

  public List<PerformanceGoal> listByReview(long reviewId, CallerIdentity caller) {
    PerformanceReview review = reviews.require(reviewId);
    access.requireReadable(review, caller);
    return goals.listByReview(reviewId);
  }

  @Transactional
  public PerformanceGoal add(long reviewId, GoalRequest req, CallerIdentity caller) {
    PerformanceReview review = reviews.require(reviewId);
    access.requireParticipant(review, caller);
    access.validate(req);
    employees.requireActive(caller.empId());
    if (NO_NEW_GOALS.contains(review.status())) {
      throw new HrmsException(ErrorCode.REVIEW_STATUS_INVALID);
    }
    long id =
        goals.insert(
            reviewId,
            review.empId(),
            req.getGoalTitle(),
            req.getGoalDescription(),
            req.getGoalCategory() == null ? DEFAULT_CATEGORY : req.getGoalCategory(),
            req.getWeightPct() == null ? BigDecimal.ZERO : req.getWeightPct(),
            req.getTargetDate(),
            caller.userId());
    PerformanceGoal created = require(id);
    audit.log(
        TABLE, id, AuditService.Action.INSERT, null, json(created), caller.userId(), null, null);
    return created;
  }

  @Transactional
  public PerformanceGoal updateProgress(
      long goalId, GoalProgressRequest req, CallerIdentity caller) {
    PerformanceGoal before = require(goalId);
    PerformanceReview review = reviews.require(before.reviewId());
    access.requireParticipant(review, caller);
    access.validate(req);
    employees.requireActive(caller.empId());
    if (NO_PROGRESS.contains(review.status())) {
      throw new HrmsException(ErrorCode.REVIEW_STATUS_INVALID);
    }
    String status = deriveStatus(req.getProgressPct(), req.getStatus(), before.status());
    goals.updateProgress(goalId, req.getProgressPct(), status, req.getComments(), caller.userId());
    PerformanceGoal after = require(goalId);
    audit.log(
        TABLE,
        goalId,
        AuditService.Action.UPDATE,
        json(before),
        json(after),
        caller.userId(),
        null,
        null);
    return after;
  }

  /** {@code update_goal_progress}: explicit status wins, else derived from the percentage. */
  static String deriveStatus(BigDecimal progressPct, @Nullable String explicit, String current) {
    if (explicit != null) {
      return explicit;
    }
    if (progressPct.compareTo(HUNDRED) >= 0) {
      return "COMPLETED";
    }
    if (progressPct.signum() > 0) {
      return "IN_PROGRESS";
    }
    return current;
  }

  private PerformanceGoal require(long goalId) {
    return goals.findById(goalId).orElseThrow(() -> new HrmsException(ErrorCode.GOAL_NOT_FOUND));
  }

  static String json(PerformanceGoal g) {
    return "{\"goalTitle\":"
        + ReviewCycleRepository.quote(g.goalTitle())
        + ",\"status\":\""
        + g.status()
        + "\",\"progressPct\":"
        + g.progressPct().toPlainString()
        + "}";
  }
}
