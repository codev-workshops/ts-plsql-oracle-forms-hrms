-- Performance slice: contract v1 PerformanceReview.selfRating has no legacy column; team and
-- "mine" listings filter by reviewer / employee within a cycle.
ALTER TABLE performance_reviews
    ADD COLUMN self_rating NUMERIC(2,1),
    ADD CONSTRAINT chk_self_rating_range CHECK (self_rating BETWEEN 1.0 AND 5.0);

CREATE INDEX idx_perf_reviews_reviewer ON performance_reviews (reviewer_emp_id, cycle_id);
CREATE INDEX idx_perf_reviews_emp ON performance_reviews (emp_id, cycle_id);
CREATE INDEX idx_perf_goals_review ON performance_goals (review_id);
