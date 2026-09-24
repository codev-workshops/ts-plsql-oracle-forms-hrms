CREATE OR REPLACE PACKAGE BODY ut_pkg_performance AS
    FUNCTION new_cycle RETURN NUMBER IS
    BEGIN
        RETURN PKG_PERFORMANCE.create_review_cycle('UT ' || TO_CHAR(SYSTIMESTAMP, 'HH24MISSFF3'),
                                                   EXTRACT(YEAR FROM SYSDATE), TRUNC(SYSDATE), TRUNC(SYSDATE) + 90,
                                                   NULL, NULL, 'UT');
    END;

    PROCEDURE create_cycle_draft IS
        v_id NUMBER; v_status VARCHAR2(20);
    BEGIN
        v_id := new_cycle();
        SELECT STATUS INTO v_status FROM REVIEW_CYCLES WHERE CYCLE_ID = v_id;
        ut.expect(v_status).to_equal('DRAFT');
    END;

    PROCEDURE open_cycle_twice IS v_id NUMBER;
    BEGIN
        v_id := new_cycle();
        PKG_PERFORMANCE.open_review_cycle(v_id, 'UT');
        PKG_PERFORMANCE.open_review_cycle(v_id, 'UT');
    END;

    PROCEDURE generate_reviews_set IS
        v_id NUMBER; v_reviews NUMBER; v_expected NUMBER;
    BEGIN
        v_id := new_cycle();
        PKG_PERFORMANCE.open_review_cycle(v_id, 'UT');
        PKG_PERFORMANCE.generate_reviews_for_cycle(v_id, 'UT');
        SELECT COUNT(*) INTO v_reviews FROM PERFORMANCE_REVIEWS WHERE CYCLE_ID = v_id;
        SELECT COUNT(*) INTO v_expected FROM EMPLOYEES
         WHERE EMPLOYMENT_STATUS = 'ACTIVE' AND MANAGER_EMP_ID IS NOT NULL;
        ut.expect(v_reviews).to_equal(v_expected);
    END;

    PROCEDURE rating_out_of_bounds IS
        v_id NUMBER; v_review NUMBER;
    BEGIN
        v_id := new_cycle();
        PKG_PERFORMANCE.open_review_cycle(v_id, 'UT');
        v_review := PKG_PERFORMANCE.create_review(v_id, 2, 1, 'UT');
        PKG_PERFORMANCE.submit_self_assessment(v_review, 'self', 'UT');
        PKG_PERFORMANCE.submit_manager_review(v_review, 5.5, 'x', 'x', 'x', 'UT');
    END;

    PROCEDURE manager_review_wrong_state IS
        v_id NUMBER; v_review NUMBER;
    BEGIN
        v_id := new_cycle();
        PKG_PERFORMANCE.open_review_cycle(v_id, 'UT');
        v_review := PKG_PERFORMANCE.create_review(v_id, 2, 1, 'UT');
        PKG_PERFORMANCE.submit_manager_review(v_review, 4.0, 'x', 'x', 'x', 'UT');
    END;
END ut_pkg_performance;
/
