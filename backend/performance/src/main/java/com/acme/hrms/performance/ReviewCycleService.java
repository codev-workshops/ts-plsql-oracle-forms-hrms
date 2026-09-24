package com.acme.hrms.performance;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.notification.NotificationService;
import com.acme.hrms.performance.PerformanceDtos.GenerateReviewsResult;
import com.acme.hrms.performance.PerformanceDtos.ReviewCycle;
import com.acme.hrms.reference.EmployeeLookup;
import com.acme.hrms.validation.dto.performance.ReviewCycleRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces {@code PKG_PERFORMANCE.create_review_cycle / open_review_cycle / close_review_cycle /
 * generate_reviews_for_cycle}. Cycle state machine (COMPONENT_MAPPING.md §6): {@code DRAFT →(open)
 * OPEN}, {@code OPEN|IN_PROGRESS|CALIBRATION →(close) CLOSED}; {@code generate-reviews} from {@code
 * DRAFT|OPEN}; {@code PUT} only in {@code DRAFT}. Every other source status is {@code -20401}.
 */
@Service
public class ReviewCycleService {

  public static final String TABLE_REVIEW_CYCLES = "REVIEW_CYCLES";
  public static final String TABLE_PERFORMANCE_REVIEWS = "PERFORMANCE_REVIEWS";

  public static final String REVIEW_INITIATED_SUBJECT = "Performance Review Initiated";
  public static final String REVIEW_INITIATED_BODY =
      "Your annual performance review has been initiated. Please complete your self-assessment.";
  static final int NOTIFICATION_PRIORITY = 5;

  public static final Set<String> CYCLE_STATUSES =
      Set.of("DRAFT", "OPEN", "IN_PROGRESS", "CALIBRATION", "CLOSED");
  public static final List<String> DEFAULT_STATUS_FILTER = List.of("OPEN", "DRAFT");
  public static final String DEFAULT_SORT = "cycleYear,desc";
  static final Map<String, String> SORTS =
      Map.of(
          "cycleYear,desc", "cycle_year desc",
          "cycleYear,asc", "cycle_year asc",
          "startDate,desc", "start_date desc",
          "startDate,asc", "start_date asc");

  public static final String MSG_OPEN = "Cannot open cycle - must be in DRAFT status";
  public static final String MSG_CLOSE =
      "Cannot close cycle - must be OPEN, IN_PROGRESS or CALIBRATION";
  public static final String MSG_GENERATE = "Cannot generate reviews - cycle must be DRAFT or OPEN";
  public static final String MSG_EDIT = "Cannot edit cycle - must be in DRAFT status";

  static final List<String> OPEN_FROM = List.of("DRAFT");
  static final List<String> CLOSE_FROM = List.of("OPEN", "IN_PROGRESS", "CALIBRATION");
  static final Set<String> GENERATE_FROM = Set.of("DRAFT", "OPEN");

  private final ReviewCycleRepository cycles;
  private final PerformanceReviewRepository reviews;
  private final EmployeeLookup employees;
  private final PerformanceAccess access;
  private final AuditService audit;
  private final NotificationService notifications;

  public ReviewCycleService(
      ReviewCycleRepository cycles,
      PerformanceReviewRepository reviews,
      EmployeeLookup employees,
      PerformanceAccess access,
      AuditService audit,
      NotificationService notifications) {
    this.cycles = cycles;
    this.reviews = reviews;
    this.employees = employees;
    this.access = access;
    this.audit = audit;
    this.notifications = notifications;
  }

  public List<ReviewCycle> list(@Nullable String status, @Nullable String sort) {
    List<String> statuses = DEFAULT_STATUS_FILTER;
    if (status != null) {
      statuses = Arrays.asList(status.split(",", -1));
      if (statuses.isEmpty() || !CYCLE_STATUSES.containsAll(statuses)) {
        throw new HrmsException(ErrorCode.VALIDATION_FAILED, "status");
      }
    }
    String orderBy = SORTS.get(sort == null ? DEFAULT_SORT : sort);
    if (orderBy == null) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "sort");
    }
    return cycles.list(statuses, orderBy);
  }

  public ReviewCycle get(long cycleId) {
    return cycles.findById(cycleId).orElseThrow(() -> new HrmsException(ErrorCode.CYCLE_NOT_FOUND));
  }

  @Transactional
  public ReviewCycle create(ReviewCycleRequest req, CallerIdentity caller) {
    validate(req);
    long id = cycles.insert(req, caller.userId());
    ReviewCycle created = get(id);
    audit.log(
        TABLE_REVIEW_CYCLES,
        id,
        AuditService.Action.INSERT,
        null,
        ReviewCycleRepository.json(created),
        caller.userId(),
        null,
        null);
    return created;
  }

  @Transactional
  public ReviewCycle update(long cycleId, ReviewCycleRequest req, CallerIdentity caller) {
    ReviewCycle before = get(cycleId);
    validate(req);
    if (!"DRAFT".equals(before.status()) || cycles.update(cycleId, req, caller.userId()) == 0) {
      throw new HrmsException(ErrorCode.CYCLE_STATUS_INVALID, MSG_EDIT, null);
    }
    ReviewCycle after = get(cycleId);
    audit.log(
        TABLE_REVIEW_CYCLES,
        cycleId,
        AuditService.Action.UPDATE,
        ReviewCycleRepository.json(before),
        ReviewCycleRepository.json(after),
        caller.userId(),
        null,
        null);
    return after;
  }

  @Transactional
  public ReviewCycle open(long cycleId, CallerIdentity caller) {
    return transition(cycleId, OPEN_FROM, "OPEN", MSG_OPEN, caller);
  }

  @Transactional
  public ReviewCycle close(long cycleId, CallerIdentity caller) {
    return transition(cycleId, CLOSE_FROM, "CLOSED", MSG_CLOSE, caller);
  }

  private ReviewCycle transition(
      long cycleId, List<String> from, String to, String message, CallerIdentity caller) {
    ReviewCycle before = get(cycleId);
    if (cycles.transition(cycleId, from, to, caller.userId()) == 0) {
      throw new HrmsException(ErrorCode.CYCLE_STATUS_INVALID, message, null);
    }
    ReviewCycle after = get(cycleId);
    audit.log(
        TABLE_REVIEW_CYCLES,
        cycleId,
        AuditService.Action.UPDATE,
        ReviewCycleRepository.json(before),
        ReviewCycleRepository.json(after),
        caller.userId(),
        null,
        null);
    return after;
  }

  /** PERF-05: set-based, idempotent per {@code (cycleId, empId)}, one transaction. */
  @Transactional
  public GenerateReviewsResult generateReviews(long cycleId, CallerIdentity caller) {
    ReviewCycle cycle = get(cycleId);
    if (!GENERATE_FROM.contains(cycle.status())) {
      throw new HrmsException(ErrorCode.CYCLE_STATUS_INVALID, MSG_GENERATE, null);
    }
    employees.requireActive(caller.empId());
    int eligible = reviews.countEligible();
    List<Long> created = reviews.generateForCycle(cycleId, caller.userId());
    for (Long reviewId : created) {
      long empId = reviews.findById(reviewId).orElseThrow().empId();
      audit.log(
          TABLE_PERFORMANCE_REVIEWS,
          reviewId,
          AuditService.Action.INSERT,
          null,
          "{\"cycleId\":" + cycleId + ",\"empId\":" + empId + ",\"status\":\"NOT_STARTED\"}",
          caller.userId(),
          null,
          null);
      notifications.enqueue(
          empId,
          null,
          NotificationService.Type.EMAIL,
          REVIEW_INITIATED_SUBJECT,
          REVIEW_INITIATED_BODY,
          NOTIFICATION_PRIORITY,
          TABLE_PERFORMANCE_REVIEWS,
          reviewId,
          caller.userId());
    }
    return new GenerateReviewsResult(cycleId, created.size(), eligible - created.size());
  }

  private void validate(ReviewCycleRequest req) {
    access.validate(req);
    if (req.getEndDate().isBefore(req.getStartDate())) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED, "End date must be on or after the start date", "endDate");
    }
  }
}
