CREATE OR REPLACE PACKAGE BODY ut_pkg_payroll AS
    FUNCTION open_period RETURN NUMBER IS v_id NUMBER;
    BEGIN
        SELECT MIN(PERIOD_ID) INTO v_id FROM PAY_PERIODS WHERE STATUS = 'OPEN';
        RETURN v_id;
    END;

    PROCEDURE create_run_open_period IS
        v_run NUMBER; v_status VARCHAR2(20);
    BEGIN
        v_run := PKG_PAYROLL.create_payroll_run(open_period(), 'REGULAR', 'UT');
        SELECT STATUS INTO v_status FROM PAYROLL_RUNS WHERE RUN_ID = v_run;
        ut.expect(v_status).to_equal('DRAFT');
    END;

    PROCEDURE create_run_closed_period IS
        v_run NUMBER; v_closed NUMBER;
    BEGIN
        SELECT MIN(PERIOD_ID) INTO v_closed FROM PAY_PERIODS WHERE STATUS = 'CLOSED';
        v_run := PKG_PAYROLL.create_payroll_run(v_closed, 'REGULAR', 'UT');
    END;

    PROCEDURE calculate_produces_earning_rows IS
        v_run NUMBER; v_rows NUMBER; v_emps NUMBER;
    BEGIN
        v_run := PKG_PAYROLL.create_payroll_run(open_period(), 'REGULAR', 'UT');
        PKG_PAYROLL.calculate_payroll(v_run, 'UT');
        SELECT COUNT(DISTINCT EMP_ID) INTO v_rows FROM PAYROLL_DETAILS WHERE RUN_ID = v_run AND ELEMENT_TYPE = 'EARNING';
        SELECT COUNT(*) INTO v_emps FROM EMPLOYEES e
         WHERE e.EMPLOYMENT_STATUS = 'ACTIVE'
           AND EXISTS (SELECT 1 FROM SALARY_RECORDS s WHERE s.EMP_ID = e.EMP_ID AND s.ACTIVE_FLAG = 'Y');
        ut.expect(v_rows).to_equal(v_emps);
    END;

    PROCEDURE approve_from_draft_fails IS
        v_run NUMBER;
    BEGIN
        v_run := PKG_PAYROLL.create_payroll_run(open_period(), 'REGULAR', 'UT');
        PKG_PAYROLL.approve_payroll(v_run, 'UT');
    END;

    PROCEDURE unlisted_state_defaults_5pct_legacy IS
    BEGIN
        -- Legacy CASE falls through to 5 %; target raises MISSING_TAX_RATE (BUG-02 fixed in P4).
        ut.expect(PKG_PAYROLL.calculate_state_tax(10000, 'ZZ', 'SINGLE')).to_equal(500);
    END;

    PROCEDURE federal_tax_bracket_boundaries_2024 IS
        TYPE t_bounds IS TABLE OF NUMBER;
        v_b t_bounds := t_bounds(11600, 47150, 100525, 191950, 243725, 609350);
        c_std CONSTANT NUMBER := 14600;   -- 2024 single standard deduction (PKG_PAYROLL constant)
        v_lo NUMBER; v_hi NUMBER;
    BEGIN
        -- calculate_federal_tax annualises the per-period amount (x12 for MONTHLY) and subtracts the
        -- standard deduction; the tax curve must be continuous at every bracket boundary.
        FOR i IN 1..v_b.COUNT LOOP
            v_lo := PKG_PAYROLL.calculate_federal_tax((v_b(i) + c_std - 12) / 12, 'SINGLE', 0, 0, 'MONTHLY');
            v_hi := PKG_PAYROLL.calculate_federal_tax((v_b(i) + c_std + 12) / 12, 'SINGLE', 0, 0, 'MONTHLY');
            ut.expect(v_hi - v_lo, 'boundary ' || v_b(i)).to_be_between(0, 1);
        END LOOP;
    END;
END ut_pkg_payroll;
/
