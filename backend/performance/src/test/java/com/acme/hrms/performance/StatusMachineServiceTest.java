package com.acme.hrms.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.notification.NotificationService;
import com.acme.hrms.performance.PerformanceDtos.PerformanceReview;
import com.acme.hrms.performance.PerformanceDtos.ReviewCycle;
import com.acme.hrms.reference.EmployeeLookup;
import com.acme.hrms.validation.dto.performance.ManagerReviewRequest;
import com.acme.hrms.validation.dto.performance.ReviewCycleRequest;
import com.acme.hrms.validation.dto.performance.SelfAssessmentRequest;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Status machines of COMPONENT_MAPPING.md §6 at the service level, with the repositories mocked:
 * which source statuses each transition accepts, the exact -20401/-20402/-20403 payloads, and that
 * AuditService / NotificationService are called (or not) on every path.
 */
class StatusMachineServiceTest {

  private static final CallerIdentity ADMIN =
      new CallerIdentity("1", 1, Set.of("PERFORMANCE:ADMIN"), "jti-1");
  private static final CallerIdentity EMP2 = new CallerIdentity("2", 2, Set.of(), "jti-2");

  private ReviewCycleRepository cycles;
  private PerformanceReviewRepository reviews;
  private EmployeeLookup employees;
  private AuditService audit;
  private NotificationService notifications;
  private ReviewCycleService cycleService;
  private PerformanceReviewService reviewService;

  @BeforeEach
  void setUp() {
    cycles = mock(ReviewCycleRepository.class);
    reviews = mock(PerformanceReviewRepository.class);
    employees = mock(EmployeeLookup.class);
    audit = mock(AuditService.class);
    notifications = mock(NotificationService.class);
    PerformanceAccess access =
        new PerformanceAccess(Validation.buildDefaultValidatorFactory().getValidator());
    cycleService = new ReviewCycleService(cycles, reviews, employees, access, audit, notifications);
    reviewService =
        new PerformanceReviewService(
            reviews, cycleService, employees, access, audit, notifications);
  }

  // ------------------------------------------------------------------ cycles

  @ParameterizedTest
  @ValueSource(strings = {"OPEN", "IN_PROGRESS", "CALIBRATION", "CLOSED"})
  void openRejectsEveryNonDraftStatus(String status) {
    when(cycles.findById(7L)).thenReturn(Optional.of(cycle(7, status)));
    when(cycles.transition(7L, List.of("DRAFT"), "OPEN", "1")).thenReturn(0);
    assertThatThrownBy(() -> cycleService.open(7, ADMIN))
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.CYCLE_STATUS_INVALID);
              assertThat(e.getMessage()).isEqualTo(ReviewCycleService.MSG_OPEN);
            });
    verifyNoInteractions(audit);
  }

  @Test
  void openFromDraftAudits() {
    when(cycles.findById(7L))
        .thenReturn(Optional.of(cycle(7, "DRAFT")), Optional.of(cycle(7, "OPEN")));
    when(cycles.transition(7L, List.of("DRAFT"), "OPEN", "1")).thenReturn(1);
    assertThat(cycleService.open(7, ADMIN).status()).isEqualTo("OPEN");
    verify(audit)
        .log(
            eq("REVIEW_CYCLES"),
            eq(7L),
            eq(AuditService.Action.UPDATE),
            anyString(),
            anyString(),
            eq("1"),
            isNull(),
            isNull());
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "CLOSED"})
  void closeRejectsDraftAndClosed(String status) {
    when(cycles.findById(7L)).thenReturn(Optional.of(cycle(7, status)));
    when(cycles.transition(eq(7L), eq(ReviewCycleService.CLOSE_FROM), eq("CLOSED"), eq("1")))
        .thenReturn(0);
    assertThatThrownBy(() -> cycleService.close(7, ADMIN))
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> assertThat(e.getMessage()).isEqualTo(ReviewCycleService.MSG_CLOSE));
    verifyNoInteractions(audit);
  }

  @Test
  void closeFromEachActiveStatus() {
    assertThat(ReviewCycleService.CLOSE_FROM).containsExactly("OPEN", "IN_PROGRESS", "CALIBRATION");
    when(cycles.findById(7L))
        .thenReturn(Optional.of(cycle(7, "CALIBRATION")), Optional.of(cycle(7, "CLOSED")));
    when(cycles.transition(7L, ReviewCycleService.CLOSE_FROM, "CLOSED", "1")).thenReturn(1);
    assertThat(cycleService.close(7, ADMIN).status()).isEqualTo("CLOSED");
    verify(audit)
        .log(
            eq("REVIEW_CYCLES"),
            eq(7L),
            eq(AuditService.Action.UPDATE),
            any(),
            any(),
            eq("1"),
            isNull(),
            isNull());
  }

  @ParameterizedTest
  @ValueSource(strings = {"IN_PROGRESS", "CALIBRATION", "CLOSED"})
  void generateRejectsStatusesOtherThanDraftOrOpen(String status) {
    when(cycles.findById(7L)).thenReturn(Optional.of(cycle(7, status)));
    assertThatThrownBy(() -> cycleService.generateReviews(7, ADMIN))
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.CYCLE_STATUS_INVALID);
              assertThat(e.getMessage()).isEqualTo(ReviewCycleService.MSG_GENERATE);
            });
    verify(reviews, never()).generateForCycle(anyLong(), anyString());
    verifyNoInteractions(notifications);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "OPEN"})
  void generateAuditsAndNotifiesEachCreatedReview(String status) {
    when(cycles.findById(7L)).thenReturn(Optional.of(cycle(7, status)));
    when(reviews.countEligible()).thenReturn(5);
    when(reviews.generateForCycle(7L, "1")).thenReturn(List.of(101L, 102L));
    when(reviews.findById(101L)).thenReturn(Optional.of(review(101, 7, 22, 21, "NOT_STARTED")));
    when(reviews.findById(102L)).thenReturn(Optional.of(review(102, 7, 23, 21, "NOT_STARTED")));

    var result = cycleService.generateReviews(7, ADMIN);

    assertThat(result.generated()).isEqualTo(2);
    assertThat(result.skipped()).isEqualTo(3);
    verify(employees).requireActive(1L);
    for (long id : new long[] {101L, 102L}) {
      verify(audit)
          .log(
              eq("PERFORMANCE_REVIEWS"),
              eq(id),
              eq(AuditService.Action.INSERT),
              isNull(),
              anyString(),
              eq("1"),
              isNull(),
              isNull());
    }
    verify(notifications)
        .enqueue(
            eq(22L),
            isNull(),
            eq(NotificationService.Type.EMAIL),
            eq(ReviewCycleService.REVIEW_INITIATED_SUBJECT),
            eq(ReviewCycleService.REVIEW_INITIATED_BODY),
            eq(5),
            eq("PERFORMANCE_REVIEWS"),
            eq(101L),
            eq("1"));
    verify(notifications)
        .enqueue(
            eq(23L),
            isNull(),
            any(),
            anyString(),
            anyString(),
            eq(5),
            anyString(),
            eq(102L),
            eq("1"));
  }

  @Test
  void updateOutsideDraftIsRejectedAfterBodyValidation() {
    when(cycles.findById(7L)).thenReturn(Optional.of(cycle(7, "OPEN")));
    ReviewCycleRequest req = new ReviewCycleRequest();
    req.setCycleName("x");
    req.setCycleYear(2025);
    req.setStartDate(LocalDate.of(2025, 1, 1));
    req.setEndDate(LocalDate.of(2025, 12, 31));
    assertThatThrownBy(() -> cycleService.update(7, req, ADMIN))
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> assertThat(e.getMessage()).isEqualTo(ReviewCycleService.MSG_EDIT));
    verify(cycles, never()).update(anyLong(), any(), anyString());
  }

  // ------------------------------------------------------------------ reviews

  @ParameterizedTest
  @ValueSource(strings = {"MANAGER_REVIEW", "MEETING_SCHEDULED", "COMPLETED", "ACKNOWLEDGED"})
  void selfAssessmentRejectsAdvancedStatuses(String status) {
    when(reviews.findById(9L)).thenReturn(Optional.of(review(9, 7, 2, 1, status)));
    when(reviews.submitSelfAssessment(9L, "done", "2")).thenReturn(0);
    SelfAssessmentRequest req = new SelfAssessmentRequest();
    req.setSelfAssessment("done");
    assertThatThrownBy(() -> reviewService.submitSelfAssessment(9, req, EMP2))
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_STATUS_INVALID);
              assertThat(e.getMessage()).isEqualTo("Review not found or not in correct status");
            });
    verifyNoInteractions(audit, notifications);
  }

  @ParameterizedTest
  @ValueSource(strings = {"NOT_STARTED", "SELF_REVIEW"})
  void selfAssessmentMovesToManagerReviewAndNotifiesReviewer(String status) {
    when(reviews.findById(9L))
        .thenReturn(
            Optional.of(review(9, 7, 2, 1, status)),
            Optional.of(review(9, 7, 2, 1, "MANAGER_REVIEW")));
    when(reviews.submitSelfAssessment(9L, "done", "2")).thenReturn(1);
    SelfAssessmentRequest req = new SelfAssessmentRequest();
    req.setSelfAssessment("done");
    assertThat(reviewService.submitSelfAssessment(9, req, EMP2).status())
        .isEqualTo("MANAGER_REVIEW");
    verify(audit)
        .log(
            eq("PERFORMANCE_REVIEWS"),
            eq(9L),
            eq(AuditService.Action.STATUS_CHANGE),
            anyString(),
            anyString(),
            eq("2"),
            isNull(),
            isNull());
    verify(notifications)
        .enqueue(
            eq(1L),
            isNull(),
            eq(NotificationService.Type.EMAIL),
            eq(PerformanceReviewService.SELF_SUBMITTED_SUBJECT),
            eq(PerformanceReviewService.SELF_SUBMITTED_BODY),
            eq(5),
            eq("PERFORMANCE_REVIEWS"),
            eq(9L),
            eq("2"));
  }

  @ParameterizedTest
  @CsvSource({"0.9", "5.1", "0.0", "9.9"})
  void managerReviewRatingOutOfRangeIs20403BeforeStatus(String rating) {
    when(reviews.findById(9L)).thenReturn(Optional.of(review(9, 7, 2, 1, "NOT_STARTED")));
    assertThatThrownBy(() -> reviewService.submitManagerReview(9, managerReq(rating), ADMIN))
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.RATING_OUT_OF_RANGE);
              assertThat(e.field()).isEqualTo("overallRating");
              assertThat(e.getMessage()).isEqualTo("Rating must be between 1.0 and 5.0");
            });
    verify(reviews, never())
        .submitManagerReview(anyLong(), any(), any(), any(), any(), any(), any(), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"NOT_STARTED", "SELF_REVIEW", "COMPLETED", "ACKNOWLEDGED"})
  void managerReviewRejectsStatusesOutsideManagerReviewOrMeeting(String status) {
    when(reviews.findById(9L)).thenReturn(Optional.of(review(9, 7, 2, 1, status)));
    when(reviews.submitManagerReview(
            anyLong(), any(), any(), any(), any(), any(), any(), anyString()))
        .thenReturn(0);
    assertThatThrownBy(() -> reviewService.submitManagerReview(9, managerReq("3.0"), ADMIN))
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_STATUS_INVALID));
    verifyNoInteractions(audit, notifications);
  }

  @ParameterizedTest
  @CsvSource({"MANAGER_REVIEW, 4.5, Exceptional", "MEETING_SCHEDULED, 1.0, Unsatisfactory"})
  void managerReviewCompletesWithLabelAndNotifiesReviewee(
      String status, String rating, String label) {
    when(reviews.findById(9L))
        .thenReturn(
            Optional.of(review(9, 7, 2, 1, status)), Optional.of(review(9, 7, 2, 1, "COMPLETED")));
    when(reviews.submitManagerReview(
            eq(9L),
            eq(new BigDecimal(rating)),
            eq(label),
            eq("ok"),
            isNull(),
            isNull(),
            isNull(),
            eq("1")))
        .thenReturn(1);
    assertThat(reviewService.submitManagerReview(9, managerReq(rating), ADMIN).status())
        .isEqualTo("COMPLETED");
    verify(audit)
        .log(
            eq("PERFORMANCE_REVIEWS"),
            eq(9L),
            eq(AuditService.Action.STATUS_CHANGE),
            anyString(),
            anyString(),
            eq("1"),
            isNull(),
            isNull());
    verify(notifications)
        .enqueue(
            eq(2L),
            isNull(),
            eq(NotificationService.Type.EMAIL),
            eq(PerformanceReviewService.COMPLETED_SUBJECT),
            eq(PerformanceReviewService.COMPLETED_BODY),
            eq(5),
            eq("PERFORMANCE_REVIEWS"),
            eq(9L),
            eq("1"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "NOT_STARTED",
        "SELF_REVIEW",
        "MANAGER_REVIEW",
        "MEETING_SCHEDULED",
        "ACKNOWLEDGED"
      })
  void acknowledgeOnlyFromCompleted(String status) {
    when(reviews.findById(9L)).thenReturn(Optional.of(review(9, 7, 2, 1, status)));
    when(reviews.acknowledge(9L, null, "2")).thenReturn(0);
    assertThatThrownBy(() -> reviewService.acknowledge(9, null, EMP2))
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_STATUS_INVALID));
    verifyNoInteractions(audit);
  }

  @Test
  void acknowledgeFromCompletedIsTerminalAndAuditedWithoutNotification() {
    when(reviews.findById(9L))
        .thenReturn(
            Optional.of(review(9, 7, 2, 1, "COMPLETED")),
            Optional.of(review(9, 7, 2, 1, "ACKNOWLEDGED")));
    when(reviews.acknowledge(9L, null, "2")).thenReturn(1);
    assertThat(reviewService.acknowledge(9, null, EMP2).status()).isEqualTo("ACKNOWLEDGED");
    verify(audit)
        .log(
            eq("PERFORMANCE_REVIEWS"),
            eq(9L),
            eq(AuditService.Action.STATUS_CHANGE),
            anyString(),
            anyString(),
            eq("2"),
            isNull(),
            isNull());
    verifyNoInteractions(notifications);
  }

  @Test
  void unknownReviewIsNotFoundBeforeScoping() {
    when(reviews.findById(9L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> reviewService.get(9, EMP2))
        .isInstanceOfSatisfying(
            HrmsException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_NOT_FOUND));
    when(reviews.findById(10L)).thenReturn(Optional.of(review(10, 7, 22, 21, "NOT_STARTED")));
    assertThatThrownBy(() -> reviewService.get(10, EMP2))
        .isInstanceOfSatisfying(
            HrmsException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
  }

  // ------------------------------------------------------------------ helpers

  private static ManagerReviewRequest managerReq(String rating) {
    ManagerReviewRequest r = new ManagerReviewRequest();
    r.setOverallRating(new BigDecimal(rating));
    r.setManagerAssessment("ok");
    return r;
  }

  static ReviewCycle cycle(long id, String status) {
    return new ReviewCycle(
        id,
        "c",
        2025,
        LocalDate.of(2025, 1, 1),
        LocalDate.of(2025, 12, 31),
        null,
        null,
        null,
        status,
        "1",
        LocalDateTime.of(2025, 1, 1, 0, 0),
        null,
        null);
  }

  static PerformanceReview review(
      long id, long cycleId, long empId, long reviewerId, String status) {
    return new PerformanceReview(
        id,
        cycleId,
        empId,
        "E",
        reviewerId,
        "R",
        "ANNUAL",
        status,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "1",
        LocalDateTime.of(2025, 1, 1, 0, 0),
        null,
        null);
  }
}
