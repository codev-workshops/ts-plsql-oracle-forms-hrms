package com.acme.hrms.performance;

import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.performance.PerformanceDtos.PerformanceGoal;
import com.acme.hrms.performance.PerformanceDtos.PerformanceReview;
import com.acme.hrms.validation.dto.performance.AcknowledgeRequest;
import com.acme.hrms.validation.dto.performance.GoalProgressRequest;
import com.acme.hrms.validation.dto.performance.GoalRequest;
import com.acme.hrms.validation.dto.performance.ManagerReviewRequest;
import com.acme.hrms.validation.dto.performance.SelfAssessmentRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/performance/reviews/**} and {@code /api/performance/goals/**}. */
@RestController
public class PerformanceReviewController {

  private final PerformanceReviewService reviews;
  private final GoalService goals;

  public PerformanceReviewController(PerformanceReviewService reviews, GoalService goals) {
    this.reviews = reviews;
    this.goals = goals;
  }

  @GetMapping("/api/performance/reviews/mine")
  @PreAuthorize("isAuthenticated()")
  public List<PerformanceReview> listMyReviews(@RequestParam(required = false) Long cycleId) {
    return reviews.listMine(CurrentCaller.require(), cycleId);
  }

  @GetMapping("/api/performance/reviews/{reviewId}")
  @PreAuthorize("isAuthenticated()")
  public PerformanceReview getReview(@PathVariable long reviewId) {
    return reviews.get(reviewId, CurrentCaller.require());
  }

  @PostMapping("/api/performance/reviews/{reviewId}/self-assessment")
  @PreAuthorize("isAuthenticated()")
  public PerformanceReview submitSelfAssessment(
      @PathVariable long reviewId, @RequestBody SelfAssessmentRequest body) {
    return reviews.submitSelfAssessment(reviewId, body, CurrentCaller.require());
  }

  @PostMapping("/api/performance/reviews/{reviewId}/manager-review")
  @PreAuthorize("isAuthenticated()")
  public PerformanceReview submitManagerReview(
      @PathVariable long reviewId, @RequestBody ManagerReviewRequest body) {
    return reviews.submitManagerReview(reviewId, body, CurrentCaller.require());
  }

  @PostMapping("/api/performance/reviews/{reviewId}/acknowledge")
  @PreAuthorize("isAuthenticated()")
  public PerformanceReview acknowledgeReview(
      @PathVariable long reviewId, @RequestBody(required = false) AcknowledgeRequest body) {
    return reviews.acknowledge(reviewId, body, CurrentCaller.require());
  }

  @GetMapping("/api/performance/reviews/{reviewId}/goals")
  @PreAuthorize("isAuthenticated()")
  public List<PerformanceGoal> listGoals(@PathVariable long reviewId) {
    return goals.listByReview(reviewId, CurrentCaller.require());
  }

  @PostMapping("/api/performance/reviews/{reviewId}/goals")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<PerformanceGoal> addGoal(
      @PathVariable long reviewId, @RequestBody GoalRequest body) {
    PerformanceGoal created = goals.add(reviewId, body, CurrentCaller.require());
    return ResponseEntity.created(URI.create("/api/performance/goals/" + created.goalId()))
        .body(created);
  }

  @PatchMapping("/api/performance/goals/{goalId}/progress")
  @PreAuthorize("isAuthenticated()")
  public PerformanceGoal updateGoalProgress(
      @PathVariable long goalId, @RequestBody GoalProgressRequest body) {
    return goals.updateProgress(goalId, body, CurrentCaller.require());
  }
}
