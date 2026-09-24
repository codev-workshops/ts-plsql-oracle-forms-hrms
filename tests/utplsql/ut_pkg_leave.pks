CREATE OR REPLACE PACKAGE ut_pkg_leave AS
    -- %suite(PKG_LEAVE characterization)
    -- %suitepath(hrms.golden)
    -- %rollback(manual)

    -- %test(submit_leave_request creates a PENDING request and increases PENDING balance)
    PROCEDURE submit_creates_pending;

    -- %test(submit_leave_request raises -20203 for an inactive leave type)
    -- %throws(-20203)
    PROCEDURE submit_invalid_type;

    -- %test(submit_leave_request raises -20210 when start after end)
    -- %throws(-20210)
    PROCEDURE submit_start_after_end;

    -- %test(submit_leave_request raises -20212 for a weekend-only range)
    -- %throws(-20212)
    PROCEDURE submit_weekend_only;

    -- %test(submit_leave_request raises -20202 on insufficient balance)
    -- %throws(-20202)
    PROCEDURE submit_insufficient_balance;

    -- %test(approve moves PENDING to USED)
    PROCEDURE approve_moves_pending_to_used;

    -- %test(BUG-04 legacy: observed holidays are not treated as non-working days)
    -- %tags(legacy_defect,BUG-04)
    PROCEDURE observed_holiday_counted_legacy;
END ut_pkg_leave;
/
