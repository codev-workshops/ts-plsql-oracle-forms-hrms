CREATE OR REPLACE PACKAGE ut_pkg_performance AS
    -- %suite(PKG_PERFORMANCE characterization)
    -- %suitepath(hrms.golden)
    -- %rollback(manual)

    -- %test(create_review_cycle returns a DRAFT cycle)
    PROCEDURE create_cycle_draft;

    -- %test(open_review_cycle twice raises -20401)
    -- %throws(-20401)
    PROCEDURE open_cycle_twice;

    -- %test(generate_reviews_for_cycle creates one review per active employee with a manager)
    PROCEDURE generate_reviews_set;

    -- %test(submit_manager_review with rating 5.5 raises -20403)
    -- %throws(-20403)
    PROCEDURE rating_out_of_bounds;

    -- %test(submit_manager_review on NOT_STARTED review raises -20402)
    -- %throws(-20402)
    PROCEDURE manager_review_wrong_state;
END ut_pkg_performance;
/
