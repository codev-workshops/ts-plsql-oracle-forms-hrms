CREATE OR REPLACE PACKAGE BODY ut_pkg_leave AS
    c_emp CONSTANT NUMBER := 1;
    c_pto CONSTANT NUMBER := 1;

    FUNCTION next_monday RETURN DATE IS
    BEGIN
        RETURN NEXT_DAY(TRUNC(SYSDATE) + 7, 'MONDAY');
    END;

    PROCEDURE submit_creates_pending IS
        v_req NUMBER; v_pending_before NUMBER; v_pending_after NUMBER; v_status VARCHAR2(20);
    BEGIN
        SELECT PENDING INTO v_pending_before FROM LEAVE_BALANCES
         WHERE EMP_ID = c_emp AND LEAVE_TYPE_ID = c_pto AND CALENDAR_YEAR = EXTRACT(YEAR FROM SYSDATE);
        v_req := PKG_LEAVE.submit_leave_request(c_emp, c_pto, next_monday(), next_monday() + 1, 'N', NULL, 'golden', 'UT');
        SELECT STATUS INTO v_status FROM LEAVE_REQUESTS WHERE REQUEST_ID = v_req;
        SELECT PENDING INTO v_pending_after FROM LEAVE_BALANCES
         WHERE EMP_ID = c_emp AND LEAVE_TYPE_ID = c_pto AND CALENDAR_YEAR = EXTRACT(YEAR FROM SYSDATE);
        ut.expect(v_status).to_equal('PENDING');
        ut.expect(v_pending_after - v_pending_before).to_equal(2);
    END;

    PROCEDURE submit_invalid_type IS v_req NUMBER;
    BEGIN
        v_req := PKG_LEAVE.submit_leave_request(c_emp, 9999, next_monday(), next_monday());
    END;

    PROCEDURE submit_start_after_end IS v_req NUMBER;
    BEGIN
        v_req := PKG_LEAVE.submit_leave_request(c_emp, c_pto, next_monday() + 1, next_monday());
    END;

    PROCEDURE submit_weekend_only IS v_req NUMBER;
    BEGIN
        v_req := PKG_LEAVE.submit_leave_request(c_emp, c_pto, next_monday() + 5, next_monday() + 6);
    END;

    PROCEDURE submit_insufficient_balance IS v_req NUMBER;
    BEGIN
        v_req := PKG_LEAVE.submit_leave_request(c_emp, c_pto, next_monday(), next_monday() + 200);
    END;

    PROCEDURE approve_moves_pending_to_used IS
        v_req NUMBER; v_used_before NUMBER; v_used_after NUMBER;
    BEGIN
        SELECT USED INTO v_used_before FROM LEAVE_BALANCES
         WHERE EMP_ID = c_emp AND LEAVE_TYPE_ID = c_pto AND CALENDAR_YEAR = EXTRACT(YEAR FROM SYSDATE);
        v_req := PKG_LEAVE.submit_leave_request(c_emp, c_pto, next_monday() + 14, next_monday() + 14);
        PKG_LEAVE.approve_leave_request(v_req, 2, NULL, 'UT');
        SELECT USED INTO v_used_after FROM LEAVE_BALANCES
         WHERE EMP_ID = c_emp AND LEAVE_TYPE_ID = c_pto AND CALENDAR_YEAR = EXTRACT(YEAR FROM SYSDATE);
        ut.expect(v_used_after - v_used_before).to_equal(1);
    END;

    PROCEDURE observed_holiday_counted_legacy IS
        v_days NUMBER; v_hol DATE;
    BEGIN
        SELECT MIN(HOLIDAY_DATE) INTO v_hol FROM HOLIDAYS
         WHERE TO_CHAR(HOLIDAY_DATE, 'DY', 'NLS_DATE_LANGUAGE=ENGLISH') IN ('SAT', 'SUN')
           AND HOLIDAY_DATE > SYSDATE;
        IF v_hol IS NULL THEN
            ut.expect(TRUE).to_be_true();  -- no weekend holiday in the seed window; nothing to characterize
            RETURN;
        END IF;
        -- legacy counts the following Monday as a working day although the holiday is observed then
        v_days := PKG_LEAVE.calculate_business_days(v_hol + 1, v_hol + 2);
        ut.expect(v_days).to_be_greater_or_equal(1);
    END;
END ut_pkg_leave;
/
