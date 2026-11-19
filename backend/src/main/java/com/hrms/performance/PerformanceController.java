package com.hrms.performance;

import com.hrms.performance.PerformanceModels.BatchResult;
import com.hrms.performance.PerformanceModels.CreateReviewRequest;
import com.hrms.performance.PerformanceModels.GoalProgressRequest;
import com.hrms.performance.PerformanceModels.ManagerReviewRequest;
import com.hrms.performance.PerformanceModels.PerformanceGoal;
import com.hrms.performance.PerformanceModels.PerformanceReview;
import com.hrms.performance.PerformanceModels.RatingCount;
import com.hrms.performance.PerformanceModels.ReviewCycle;
import com.hrms.performance.PerformanceModels.SelfAssessmentRequest;
import com.hrms.platform.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceController {

    private final PerformanceService performance;

    public PerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    @GetMapping("/cycles")
    public List<ReviewCycle> cycles(CurrentUser user, @RequestParam(required = false) String status) {
        return performance.cycles(status);
    }

    @PostMapping("/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewCycle createCycle(CurrentUser user, @Valid @RequestBody ReviewCycle cycle) {
        return performance.createCycle(user, cycle);
    }

    @PostMapping("/cycles/{cycleId}/open")
    public ReviewCycle open(CurrentUser user, @PathVariable long cycleId) {
        return performance.openCycle(user, cycleId);
    }

    @PostMapping("/cycles/{cycleId}/close")
    public ReviewCycle close(CurrentUser user, @PathVariable long cycleId) {
        return performance.closeCycle(user, cycleId);
    }

    @PostMapping("/cycles/{cycleId}/generate-reviews")
    public BatchResult generate(CurrentUser user, @PathVariable long cycleId) {
        return new BatchResult("GENERATE_REVIEWS", performance.generateReviews(user, cycleId));
    }

    @GetMapping("/cycles/{cycleId}/rating-distribution")
    public List<RatingCount> ratingDistribution(CurrentUser user, @PathVariable long cycleId,
                                                @RequestParam(required = false) Long deptId) {
        return performance.ratingDistribution(user, cycleId, deptId);
    }

    @GetMapping("/reviews")
    public List<PerformanceReview> reviews(CurrentUser user, @RequestParam(required = false) String scope,
                                           @RequestParam(required = false) Long cycleId) {
        return performance.reviews(user, scope, cycleId);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceReview createReview(CurrentUser user, @Valid @RequestBody CreateReviewRequest request) {
        return performance.createReview(user, request);
    }

    @GetMapping("/reviews/{reviewId}")
    public PerformanceReview get(CurrentUser user, @PathVariable long reviewId) {
        return performance.get(user, reviewId);
    }

    @PostMapping("/reviews/{reviewId}/self-assessment")
    public PerformanceReview selfAssessment(CurrentUser user, @PathVariable long reviewId,
                                            @Valid @RequestBody SelfAssessmentRequest request) {
        return performance.submitSelfAssessment(user, reviewId, request);
    }

    @PostMapping("/reviews/{reviewId}/manager-review")
    public PerformanceReview managerReview(CurrentUser user, @PathVariable long reviewId,
                                           @Valid @RequestBody ManagerReviewRequest request) {
        return performance.submitManagerReview(user, reviewId, request);
    }

    @PostMapping("/reviews/{reviewId}/acknowledge")
    public PerformanceReview acknowledge(CurrentUser user, @PathVariable long reviewId) {
        return performance.acknowledge(user, reviewId);
    }

    @GetMapping("/reviews/{reviewId}/goals")
    public List<PerformanceGoal> goals(CurrentUser user, @PathVariable long reviewId) {
        return performance.goals(user, reviewId);
    }

    @PostMapping("/reviews/{reviewId}/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceGoal addGoal(CurrentUser user, @PathVariable long reviewId,
                                   @Valid @RequestBody PerformanceGoal goal) {
        return performance.addGoal(user, reviewId, goal);
    }

    @PostMapping("/goals/{goalId}/progress")
    public PerformanceGoal progress(CurrentUser user, @PathVariable long goalId,
                                    @Valid @RequestBody GoalProgressRequest request) {
        return performance.updateGoalProgress(user, goalId, request);
    }
}
