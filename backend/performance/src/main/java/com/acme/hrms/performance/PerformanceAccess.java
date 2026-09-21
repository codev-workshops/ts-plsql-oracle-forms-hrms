package com.acme.hrms.performance;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.performance.PerformanceDtos.PerformanceReview;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Row-level scoping rule of contracts/p1-performance/openapi.yaml plus the deferred body validation
 * that keeps the frozen evaluation order (error-codes.md §3: existence → scoping → Bean Validation
 * → domain). Controllers therefore never annotate bodies with {@code @Valid}.
 */
@Component
public class PerformanceAccess {

  public static final String VIEW = "PERFORMANCE:VIEW";
  public static final String ADMIN = "PERFORMANCE:ADMIN";

  private final Validator validator;

  public PerformanceAccess(Validator validator) {
    this.validator = validator;
  }

  static boolean isReviewee(PerformanceReview review, CallerIdentity caller) {
    return review.empId() == caller.empId();
  }

  static boolean isReviewer(PerformanceReview review, CallerIdentity caller) {
    return review.reviewerEmpId() == caller.empId();
  }

  static boolean hasViewOrAdmin(CallerIdentity caller) {
    return caller.authorities().contains(VIEW) || caller.authorities().contains(ADMIN);
  }

  /** getReview / listGoals: reviewee, reviewer or PERFORMANCE:VIEW/ADMIN. */
  public void requireReadable(PerformanceReview review, CallerIdentity caller) {
    if (!isReviewee(review, caller) && !isReviewer(review, caller) && !hasViewOrAdmin(caller)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  /** addGoal / updateGoalProgress: reviewee or reviewer only. */
  public void requireParticipant(PerformanceReview review, CallerIdentity caller) {
    if (!isReviewee(review, caller) && !isReviewer(review, caller)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  public void requireReviewee(PerformanceReview review, CallerIdentity caller) {
    if (!isReviewee(review, caller)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  public void requireReviewer(PerformanceReview review, CallerIdentity caller) {
    if (!isReviewer(review, caller)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  /** Bean Validation of a request body; rendered as {@code VALIDATION_FAILED} with details. */
  public <T> T validate(T body) {
    Set<ConstraintViolation<T>> violations = validator.validate(body);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    return body;
  }
}
