package com.acme.hrms.performance;

import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.performance.PerformanceDtos.GenerateReviewsResult;
import com.acme.hrms.performance.PerformanceDtos.PageOfPerformanceReview;
import com.acme.hrms.performance.PerformanceDtos.RatingDistributionRow;
import com.acme.hrms.performance.PerformanceDtos.ReviewCycle;
import com.acme.hrms.performance.PerformanceDtos.TeamReviewRow;
import com.acme.hrms.validation.dto.performance.ReviewCycleRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/performance/cycles/**} of contracts/p1-performance/openapi.yaml. Bodies are validated
 * inside the services (after existence/scoping) so the frozen evaluation order holds.
 */
@RestController
public class ReviewCycleController {

  private final ReviewCycleService cycles;
  private final PerformanceReviewService reviews;

  public ReviewCycleController(ReviewCycleService cycles, PerformanceReviewService reviews) {
    this.cycles = cycles;
    this.reviews = reviews;
  }

  @GetMapping("/api/performance/cycles")
  @PreAuthorize("isAuthenticated()")
  public List<ReviewCycle> listCycles(
      @RequestParam(required = false) String status, @RequestParam(required = false) String sort) {
    return cycles.list(status, sort);
  }

  @PostMapping("/api/performance/cycles")
  @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
  public ResponseEntity<ReviewCycle> createCycle(@RequestBody ReviewCycleRequest body) {
    ReviewCycle created = cycles.create(body, CurrentCaller.require());
    return ResponseEntity.created(URI.create("/api/performance/cycles/" + created.cycleId()))
        .body(created);
  }

  @GetMapping("/api/performance/cycles/{cycleId}")
  @PreAuthorize("isAuthenticated()")
  public ReviewCycle getCycle(@PathVariable long cycleId) {
    return cycles.get(cycleId);
  }

  @PutMapping("/api/performance/cycles/{cycleId}")
  @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
  public ReviewCycle updateCycle(@PathVariable long cycleId, @RequestBody ReviewCycleRequest body) {
    return cycles.update(cycleId, body, CurrentCaller.require());
  }

  @PostMapping("/api/performance/cycles/{cycleId}/open")
  @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
  public ReviewCycle openCycle(@PathVariable long cycleId) {
    return cycles.open(cycleId, CurrentCaller.require());
  }

  @PostMapping("/api/performance/cycles/{cycleId}/close")
  @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
  public ReviewCycle closeCycle(@PathVariable long cycleId) {
    return cycles.close(cycleId, CurrentCaller.require());
  }

  @PostMapping("/api/performance/cycles/{cycleId}/generate-reviews")
  @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
  public GenerateReviewsResult generateReviews(@PathVariable long cycleId) {
    return cycles.generateReviews(cycleId, CurrentCaller.require());
  }

  @GetMapping("/api/performance/cycles/{cycleId}/reviews")
  @PreAuthorize("hasAnyAuthority('PERFORMANCE:VIEW','PERFORMANCE:ADMIN')")
  public PageOfPerformanceReview listCycleReviews(
      @PathVariable long cycleId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return reviews.listByCycle(cycleId, status, page, size);
  }

  @GetMapping("/api/performance/cycles/{cycleId}/team-reviews")
  @PreAuthorize("isAuthenticated()")
  public List<TeamReviewRow> listTeamReviews(@PathVariable long cycleId) {
    return reviews.teamReviews(cycleId, CurrentCaller.require());
  }

  @GetMapping("/api/performance/cycles/{cycleId}/rating-distribution")
  @PreAuthorize("hasAnyAuthority('PERFORMANCE:VIEW','PERFORMANCE:ADMIN')")
  public List<RatingDistributionRow> getRatingDistribution(
      @PathVariable long cycleId, @RequestParam(required = false) Long deptId) {
    return reviews.ratingDistribution(cycleId, deptId);
  }
}
