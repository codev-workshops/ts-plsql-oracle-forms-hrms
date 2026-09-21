package com.acme.hrms.performance;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.notification.NotificationService;
import com.acme.hrms.performance.PerformanceDtos.PageOfPerformanceReview;
import com.acme.hrms.performance.PerformanceDtos.PerformanceReview;
import com.acme.hrms.performance.PerformanceDtos.RatingDistributionRow;
import com.acme.hrms.performance.PerformanceDtos.TeamReviewRow;
import com.acme.hrms.reference.EmployeeLookup;
import com.acme.hrms.validation.dto.performance.AcknowledgeRequest;
import com.acme.hrms.validation.dto.performance.ManagerReviewRequest;
import com.acme.hrms.validation.dto.performance.SelfAssessmentRequest;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces {@code PKG_PERFORMANCE.submit_self_assessment / submit_manager_review /
 * acknowledge_review / get_team_reviews / get_rating_distribution}. Review state machine
 * (COMPONENT_MAPPING.md §6): {@code NOT_STARTED|SELF_REVIEW → MANAGER_REVIEW →(or
 * MEETING_SCHEDULED) COMPLETED → ACKNOWLEDGED}; any other source status is {@code -20402}.
 */
@Service
public class PerformanceReviewService {

  public static final String TABLE = ReviewCycleService.TABLE_PERFORMANCE_REVIEWS;

  public static final String SELF_SUBMITTED_SUBJECT =
      "Self-Assessment Submitted - Ready for Manager Review";
  public static final String SELF_SUBMITTED_BODY =
      "An employee has completed their self-assessment. Please proceed with the manager review.";
  public static final String COMPLETED_SUBJECT = "Performance Review Completed";
  public static final String COMPLETED_BODY =
      "Your manager has completed your performance review. Please review and acknowledge.";

  public static final Set<String> REVIEW_STATUSES =
      Set.of(
          "NOT_STARTED",
          "SELF_REVIEW",
          "MANAGER_REVIEW",
          "MEETING_SCHEDULED",
          "COMPLETED",
          "ACKNOWLEDGED");
  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 200;

  static final BigDecimal MIN_RATING = new BigDecimal("1.0");
  static final BigDecimal MAX_RATING = new BigDecimal("5.0");

  private final PerformanceReviewRepository reviews;
  private final ReviewCycleService cycles;
  private final EmployeeLookup employees;
  private final PerformanceAccess access;
  private final AuditService audit;
  private final NotificationService notifications;

  public PerformanceReviewService(
      PerformanceReviewRepository reviews,
      ReviewCycleService cycles,
      EmployeeLookup employees,
      PerformanceAccess access,
      AuditService audit,
      NotificationService notifications) {
    this.reviews = reviews;
    this.cycles = cycles;
    this.employees = employees;
    this.access = access;
    this.audit = audit;
    this.notifications = notifications;
  }

  public PerformanceReview require(long reviewId) {
    return reviews
        .findById(reviewId)
        .orElseThrow(() -> new HrmsException(ErrorCode.REVIEW_NOT_FOUND));
  }

  /** GET /reviews/{id}: existence ({@code REVIEW_NOT_FOUND}) before scoping (FORBIDDEN). */
  public PerformanceReview get(long reviewId, CallerIdentity caller) {
    PerformanceReview review = require(reviewId);
    access.requireReadable(review, caller);
    return review;
  }

  public PageOfPerformanceReview listByCycle(
      long cycleId, @Nullable String status, @Nullable Integer page, @Nullable Integer size) {
    cycles.get(cycleId);
    List<String> statuses = List.of();
    if (status != null) {
      statuses = Arrays.asList(status.split(",", -1));
      if (!REVIEW_STATUSES.containsAll(statuses)) {
        throw new HrmsException(ErrorCode.VALIDATION_FAILED, "status");
      }
    }
    int p = page == null ? 0 : page;
    int s = size == null ? DEFAULT_PAGE_SIZE : size;
    if (p < 0) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "page");
    }
    if (s < 1 || s > MAX_PAGE_SIZE) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "size");
    }
    return reviews.pageByCycle(cycleId, statuses, p, s);
  }

  /** GET /reviews/mine: identity is {@code jwt.empId} only (SEC rule, never a query param). */
  public List<PerformanceReview> listMine(CallerIdentity caller, @Nullable Long cycleId) {
    if (cycleId != null && cycleId < 1) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "cycleId");
    }
    return reviews.listMine(caller.empId(), cycleId);
  }

  @Transactional
  public PerformanceReview submitSelfAssessment(
      long reviewId, SelfAssessmentRequest req, CallerIdentity caller) {
    PerformanceReview before = require(reviewId);
    access.requireReviewee(before, caller);
    access.validate(req);
    employees.requireActive(caller.empId());
    if (reviews.submitSelfAssessment(reviewId, req.getSelfAssessment(), caller.userId()) == 0) {
      throw new HrmsException(ErrorCode.REVIEW_STATUS_INVALID);
    }
    PerformanceReview after = require(reviewId);
    auditStatus(before, after, caller);
    notifications.enqueue(
        after.reviewerEmpId(),
        null,
        NotificationService.Type.EMAIL,
        SELF_SUBMITTED_SUBJECT,
        SELF_SUBMITTED_BODY,
        ReviewCycleService.NOTIFICATION_PRIORITY,
        TABLE,
        reviewId,
        caller.userId());
    return after;
  }

  /**
   * Order frozen by error-codes.md: scoping → {@code -20403} rating bounds → Bean Validation →
   * {@code -20402} status. The legacy package checked the rating before touching the row.
   */
  @Transactional
  public PerformanceReview submitManagerReview(
      long reviewId, ManagerReviewRequest req, CallerIdentity caller) {
    PerformanceReview before = require(reviewId);
    access.requireReviewer(before, caller);
    BigDecimal rating = req.getOverallRating();
    if (rating != null && (rating.compareTo(MIN_RATING) < 0 || rating.compareTo(MAX_RATING) > 0)) {
      throw new HrmsException(ErrorCode.RATING_OUT_OF_RANGE, "overallRating");
    }
    access.validate(req);
    employees.requireActive(caller.empId());
    int updated =
        reviews.submitManagerReview(
            reviewId,
            rating,
            RatingLabel.of(rating),
            req.getManagerAssessment(),
            req.getStrengths(),
            req.getImprovementAreas(),
            req.getDevelopmentPlan(),
            caller.userId());
    if (updated == 0) {
      throw new HrmsException(ErrorCode.REVIEW_STATUS_INVALID);
    }
    PerformanceReview after = require(reviewId);
    auditStatus(before, after, caller);
    notifications.enqueue(
        after.empId(),
        null,
        NotificationService.Type.EMAIL,
        COMPLETED_SUBJECT,
        COMPLETED_BODY,
        ReviewCycleService.NOTIFICATION_PRIORITY,
        TABLE,
        reviewId,
        caller.userId());
    return after;
  }

  @Transactional
  public PerformanceReview acknowledge(
      long reviewId, @Nullable AcknowledgeRequest req, CallerIdentity caller) {
    PerformanceReview before = require(reviewId);
    access.requireReviewee(before, caller);
    String comments = null;
    if (req != null) {
      access.validate(req);
      comments = req.getEmployeeComments();
    }
    employees.requireActive(caller.empId());
    if (reviews.acknowledge(reviewId, comments, caller.userId()) == 0) {
      throw new HrmsException(ErrorCode.REVIEW_STATUS_INVALID);
    }
    PerformanceReview after = require(reviewId);
    auditStatus(before, after, caller);
    return after;
  }

  public List<TeamReviewRow> teamReviews(long cycleId, CallerIdentity caller) {
    cycles.get(cycleId);
    return reviews.teamReviews(caller.empId(), cycleId);
  }

  public List<RatingDistributionRow> ratingDistribution(long cycleId, @Nullable Long deptId) {
    cycles.get(cycleId);
    if (deptId != null && deptId < 1) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "deptId");
    }
    return reviews.ratingDistribution(cycleId, deptId);
  }

  private void auditStatus(PerformanceReview before, PerformanceReview after, CallerIdentity c) {
    audit.log(
        TABLE,
        after.reviewId(),
        AuditService.Action.STATUS_CHANGE,
        statusJson(before),
        statusJson(after),
        c.userId(),
        null,
        null);
  }

  static String statusJson(PerformanceReview r) {
    return "{\"status\":\""
        + r.status()
        + "\",\"overallRating\":"
        + (r.overallRating() == null ? "null" : r.overallRating().toPlainString())
        + "}";
  }
}
