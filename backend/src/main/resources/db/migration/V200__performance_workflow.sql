-- P4 Performance (reserved Flyway range V200-V299, parallel with P5).
-- generate_reviews_for_cycle relied on DUP_VAL_ON_INDEX to skip existing reviews, but no unique key
-- existed in the Oracle DDL, so re-running it duplicated reviews. One review per cycle, employee and type.
CREATE UNIQUE INDEX uq_performance_reviews_cycle_emp_type
    ON hrms.performance_reviews (cycle_id, emp_id, review_type);

-- DEF-21: update_goal_progress never validated 0-100. NOT VALID keeps legacy rows loadable (P8 cleans
-- them up) while every new or updated row is checked; the API validates first (400 VALIDATION_FAILED).
ALTER TABLE hrms.performance_goals
    ADD CONSTRAINT chk_goal_progress_range CHECK (progress_pct BETWEEN 0 AND 100) NOT VALID;
ALTER TABLE hrms.performance_goals
    ADD CONSTRAINT chk_goal_weight_range CHECK (weight_pct BETWEEN 0 AND 100) NOT VALID;
ALTER TABLE hrms.review_cycles
    ADD CONSTRAINT chk_cycle_dates CHECK (end_date >= start_date) NOT VALID;
