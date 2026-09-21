CREATE OR REPLACE PACKAGE ut_pkg_security AS
    -- %suite(PKG_SECURITY characterization)
    -- %suitepath(hrms.golden)
    -- %rollback(manual)

    -- %beforeall
    PROCEDURE reset_seed;

    -- %test(authenticate returns a USER_SESSIONS id for a seeded e-mail)
    PROCEDURE authenticate_seed_user;

    -- %test(authenticate raises -20301 for an unknown e-mail)
    -- %throws(-20301)
    PROCEDURE authenticate_unknown_user;

    -- %test(SEC-01 legacy: authenticate accepts any password for a seeded user)
    -- %tags(legacy_defect,SEC-01)
    PROCEDURE authenticate_ignores_password_legacy;

    -- %test(SEC-05 legacy: five failures do NOT lock the account)
    -- %tags(legacy_defect,SEC-05)
    PROCEDURE no_lockout_after_five_failures_legacy;

    -- %test(change_password raises -20310 when shorter than 8)
    -- %throws(-20310)
    PROCEDURE change_password_too_short;

    -- %test(change_password raises -20311 when no upper-case letter)
    -- %throws(-20311)
    PROCEDURE change_password_no_upper;

    -- %test(change_password raises -20312 when no digit)
    -- %throws(-20312)
    PROCEDURE change_password_no_digit;

    -- %test(rule order is length, then upper-case, then digit)
    PROCEDURE change_password_rule_order;

    -- %test(has_permission truth table for grades 1-10 x module x action matches ROLE_PERMISSIONS seed)
    PROCEDURE has_permission_truth_table;

    -- %test(logout closes the session and is_session_valid becomes FALSE)
    PROCEDURE logout_closes_session;

    -- %test(SEC-07 legacy: encrypt_ssn is reversible with the hard-coded key)
    -- %tags(legacy_defect,SEC-07)
    PROCEDURE encrypt_ssn_roundtrip_legacy;
END ut_pkg_security;
/
