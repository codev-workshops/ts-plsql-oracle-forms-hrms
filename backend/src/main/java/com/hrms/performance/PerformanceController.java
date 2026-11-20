package com.hrms.performance;

import com.hrms.auth.HrmsPrincipal;
import com.hrms.common.ApiException;
import com.hrms.common.ErrorCode;
import com.hrms.performance.PerformanceModels.Acknowledgement;
import com.hrms.performance.PerformanceModels.AddGoal;
import com.hrms.performance.PerformanceModels.CreateReviewCycle;
import com.hrms.performance.PerformanceModels.GenerateReviewsResult;
import com.hrms.performance.PerformanceModels.GoalProgress;
import com.hrms.performance.PerformanceModels.ManagerReview;
import com.hrms.performance.PerformanceModels.PerformanceGoal;
import com.hrms.performance.PerformanceModels.PerformanceReview;
import com.hrms.performance.PerformanceModels.RatingBucket;
import com.hrms.performance.PerformanceModels.ReviewCycle;
import com.hrms.performance.PerformanceModels.ReviewRole;
import com.hrms.performance.PerformanceModels.ReviewStatus;
import com.hrms.performance.PerformanceModels.SelfAssessment;
import com.hrms.performance.PerformanceService.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Performance API (contract amendment v1.1-P4). HRMS_PERFORMANCE had no permission check (FRM-076), so
 * every authenticated user reaches it and rows are scoped in {@link PerformanceService} (OQ-09). The
 * block-level DML policy becomes commands only: cycles change through create/open/close
 * (PERFORMANCE:ADMIN, FRM-071), review status only through workflow commands (FRM-072/073), and
 * nothing is deleted (405 DELETE_FORBIDDEN, FRM-071/072/074).
 */
@RestController
@RequestMapping("/api/v1/performance")
@PreAuthorize("isAuthenticated()")
public class PerformanceController {

    private final PerformanceService performance;

    public PerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    @GetMapping("/cycles")
    List<ReviewCycle> cycles(@RequestParam(defaultValue = "false") boolean includeClosed) {
        return performance.cycles(includeClosed);
    }

    @PostMapping("/cycles")
    @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
    ResponseEntity<ReviewCycle> createCycle(@AuthenticationPrincipal HrmsPrincipal p,
            @Valid @RequestBody CreateReviewCycle body, HttpServletRequest http) {
        ReviewCycle created = performance.createCycle(caller(p, http), body);
        return ResponseEntity.created(URI.create("/api/v1/performance/cycles/" + created.cycleId())).body(created);
    }

    @GetMapping("/cycles/{cycleId}")
    ReviewCycle cycle(@PathVariable long cycleId) {
        return performance.cycle(cycleId);
    }

    @DeleteMapping("/cycles/{cycleId}")
    void deleteCycle(@PathVariable long cycleId) {
        throw forbiddenDelete("Review cycles cannot be deleted; close the cycle instead");
    }

    @PostMapping("/cycles/{cycleId}/open")
    @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
    ReviewCycle open(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long cycleId, HttpServletRequest http) {
        return performance.openCycle(caller(p, http), cycleId);
    }

    @PostMapping("/cycles/{cycleId}/close")
    @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
    ReviewCycle close(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long cycleId, HttpServletRequest http) {
        return performance.closeCycle(caller(p, http), cycleId);
    }

    @PostMapping("/cycles/{cycleId}/reviews/generate")
    @PreAuthorize("hasAuthority('PERFORMANCE:ADMIN')")
    GenerateReviewsResult generate(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long cycleId,
            HttpServletRequest http) {
        return performance.generateReviews(caller(p, http), cycleId);
    }

    @GetMapping("/cycles/{cycleId}/reviews")
    @PreAuthorize("hasAuthority('PERFORMANCE:VIEW')")
    List<PerformanceReview> cycleReviews(@PathVariable long cycleId,
            @RequestParam(required = false) ReviewStatus status) {
        return performance.cycleReviews(cycleId, status);
    }

    @GetMapping("/cycles/{cycleId}/rating-distribution")
    @PreAuthorize("hasAuthority('PERFORMANCE:VIEW')")
    List<RatingBucket> ratingDistribution(@PathVariable long cycleId, @RequestParam(required = false) Long deptId) {
        return performance.ratingDistribution(cycleId, deptId);
    }

    @GetMapping("/reviews")
    List<PerformanceReview> myReviews(@AuthenticationPrincipal HrmsPrincipal p,
            @RequestParam(defaultValue = "EMPLOYEE") ReviewRole role, @RequestParam(required = false) Long cycleId) {
        return performance.reviewsOf(p.empId(), role, cycleId);
    }

    @GetMapping("/reviews/{reviewId}")
    PerformanceReview review(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long reviewId) {
        return performance.review(p, reviewId);
    }

    @DeleteMapping("/reviews/{reviewId}")
    void deleteReview(@PathVariable long reviewId) {
        throw forbiddenDelete("Performance reviews cannot be deleted");
    }

    @PostMapping("/reviews/{reviewId}/self-assessment")
    PerformanceReview selfAssessment(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long reviewId,
            @Valid @RequestBody SelfAssessment body, HttpServletRequest http) {
        return performance.submitSelfAssessment(caller(p, http), reviewId, body);
    }

    @PostMapping("/reviews/{reviewId}/manager-review")
    PerformanceReview managerReview(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long reviewId,
            @Valid @RequestBody ManagerReview body, HttpServletRequest http) {
        return performance.submitManagerReview(caller(p, http), reviewId, body);
    }

    @PostMapping("/reviews/{reviewId}/acknowledge")
    PerformanceReview acknowledge(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long reviewId,
            @Valid @RequestBody(required = false) Acknowledgement body, HttpServletRequest http) {
        return performance.acknowledge(caller(p, http), reviewId, body);
    }

    @GetMapping("/reviews/{reviewId}/goals")
    List<PerformanceGoal> goals(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long reviewId) {
        return performance.goals(p, reviewId);
    }

    @PostMapping("/reviews/{reviewId}/goals")
    ResponseEntity<PerformanceGoal> addGoal(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long reviewId,
            @Valid @RequestBody AddGoal body, HttpServletRequest http) {
        PerformanceGoal created = performance.addGoal(caller(p, http), reviewId, body);
        return ResponseEntity.created(URI.create("/api/v1/performance/goals/" + created.goalId())).body(created);
    }

    @GetMapping("/goals/{goalId}")
    PerformanceGoal goal(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long goalId) {
        return performance.goal(p, goalId);
    }

    @DeleteMapping("/goals/{goalId}")
    void deleteGoal(@PathVariable long goalId) {
        throw forbiddenDelete("Performance goals cannot be deleted");
    }

    @PostMapping("/goals/{goalId}/progress")
    PerformanceGoal progress(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long goalId,
            @Valid @RequestBody GoalProgress body, HttpServletRequest http) {
        return performance.updateProgress(caller(p, http), goalId, body);
    }

    private static Caller caller(HrmsPrincipal p, HttpServletRequest http) {
        return new Caller(p, http.getRemoteAddr());
    }

    private static ApiException forbiddenDelete(String message) {
        return new ApiException(ErrorCode.DELETE_FORBIDDEN, message);
    }
}
