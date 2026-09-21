CREATE OR REPLACE PACKAGE ut_pkg_payroll AS
    -- %suite(PKG_PAYROLL characterization)
    -- %suitepath(hrms.golden)
    -- %rollback(manual)

    -- %test(create_payroll_run on an OPEN period returns a DRAFT run)
    PROCEDURE create_run_open_period;

    -- %test(create_payroll_run on a CLOSED period raises -20102)
    -- %throws(-20102)
    PROCEDURE create_run_closed_period;

    -- %test(calculate_payroll produces one EARNING row per active salaried employee)
    PROCEDURE calculate_produces_earning_rows;

    -- %test(approve_payroll requires CALCULATED status: -20103 from DRAFT)
    -- %throws(-20103)
    PROCEDURE approve_from_draft_fails;

    -- %test(BUG-02 legacy: unlisted state defaults to 5 percent state tax)
    -- %tags(legacy_defect,BUG-02)
    PROCEDURE unlisted_state_defaults_5pct_legacy;

    -- %test(federal tax at 2024 single bracket boundaries)
    PROCEDURE federal_tax_bracket_boundaries_2024;
END ut_pkg_payroll;
/
