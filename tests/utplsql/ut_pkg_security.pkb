CREATE OR REPLACE PACKAGE BODY ut_pkg_security AS
    c_email  CONSTANT VARCHAR2(100) := 'sarah.chen@acme-corp.com';   -- EMP 1, seed 02_employee_data.sql
    c_nobody CONSTANT VARCHAR2(100) := 'nobody@acme-corp.com';

    PROCEDURE reset_seed IS
    BEGIN
        -- Seed is reloaded by tools/parallel-run before every suite; nothing to do here.
        NULL;
    END;

    PROCEDURE authenticate_seed_user IS
        v_session NUMBER;
        v_status  USER_SESSIONS.SESSION_STATUS%TYPE;
    BEGIN
        v_session := PKG_SECURITY.authenticate(c_email, 'irrelevant', '127.0.0.1');
        SELECT SESSION_STATUS INTO v_status FROM USER_SESSIONS WHERE SESSION_ID = v_session;
        ut.expect(v_status).to_equal('ACTIVE');
    END;

    PROCEDURE authenticate_unknown_user IS
        v_session NUMBER;
    BEGIN
        v_session := PKG_SECURITY.authenticate(c_nobody, 'x', NULL);
    END;

    PROCEDURE authenticate_ignores_password_legacy IS
        v_a NUMBER; v_b NUMBER;
    BEGIN
        v_a := PKG_SECURITY.authenticate(c_email, 'one', NULL);
        v_b := PKG_SECURITY.authenticate(c_email, 'completely-different', NULL);
        ut.expect(v_a).to_be_not_null();
        ut.expect(v_b).to_be_greater_than(v_a);
    END;

    PROCEDURE no_lockout_after_five_failures_legacy IS
        v_session NUMBER;
    BEGIN
        FOR i IN 1..5 LOOP
            BEGIN
                v_session := PKG_SECURITY.authenticate(c_nobody, 'bad', NULL);
            EXCEPTION WHEN OTHERS THEN
                ut.expect(SQLCODE).to_equal(-20301);
            END;
        END LOOP;
        v_session := PKG_SECURITY.authenticate(c_email, 'bad', NULL);
        ut.expect(v_session).to_be_not_null();   -- target: 429 RATE_LIMITED after 5 (SEC-05 fixed)
    END;

    PROCEDURE change_password_too_short IS
    BEGIN
        PKG_SECURITY.change_password(1, 'old', 'Ab1');
    END;

    PROCEDURE change_password_no_upper IS
    BEGIN
        PKG_SECURITY.change_password(1, 'old', 'abcdefg1');
    END;

    PROCEDURE change_password_no_digit IS
    BEGIN
        PKG_SECURITY.change_password(1, 'old', 'Abcdefgh');
    END;

    PROCEDURE change_password_rule_order IS
    BEGIN
        BEGIN
            PKG_SECURITY.change_password(1, 'old', 'abc');          -- short AND no upper AND no digit
            ut.fail('expected -20310');
        EXCEPTION WHEN OTHERS THEN ut.expect(SQLCODE).to_equal(-20310); END;
        BEGIN
            PKG_SECURITY.change_password(1, 'old', 'abcdefghij');   -- long enough, no upper, no digit
            ut.fail('expected -20311');
        EXCEPTION WHEN OTHERS THEN ut.expect(SQLCODE).to_equal(-20311); END;
    END;

    -- Legacy rule (PKG_SECURITY.has_permission body): grade>=8 -> everything; grade>=5 -> any VIEW;
    -- everyone -> LEAVE:CREATE, LEAVE:VIEW, EMPLOYEE:VIEW. The target seeds ROLES/ROLE_PERMISSIONS
    -- (V2__p0_auth_foundation.sql) so that the resolved MODULE:ACTION authorities give the same table.
    PROCEDURE has_permission_truth_table IS
        v_expected BOOLEAN;
    BEGIN
        FOR r IN (SELECT e.EMP_ID, j.GRADE_ID, m.MODULE, a.ACTION
                  FROM EMPLOYEES e JOIN JOB_TITLES j ON e.JOB_ID = j.JOB_ID
                  CROSS JOIN (SELECT 'PAYROLL' MODULE FROM DUAL UNION ALL SELECT 'EMPLOYEE' FROM DUAL
                              UNION ALL SELECT 'LEAVE' FROM DUAL UNION ALL SELECT 'ADMIN' FROM DUAL
                              UNION ALL SELECT 'REPORTS' FROM DUAL) m
                  CROSS JOIN (SELECT 'VIEW' ACTION FROM DUAL UNION ALL SELECT 'EDIT' FROM DUAL
                              UNION ALL SELECT 'APPROVE' FROM DUAL UNION ALL SELECT 'CREATE' FROM DUAL) a)
        LOOP
            v_expected := r.GRADE_ID >= 8
                       OR (r.ACTION = 'VIEW' AND r.GRADE_ID >= 5)
                       OR (r.MODULE = 'LEAVE' AND r.ACTION IN ('CREATE', 'VIEW'))
                       OR (r.MODULE = 'EMPLOYEE' AND r.ACTION = 'VIEW');
            ut.expect(PKG_SECURITY.has_permission(r.EMP_ID, r.MODULE, r.ACTION),
                      r.EMP_ID || '/' || r.MODULE || ':' || r.ACTION).to_equal(v_expected);
        END LOOP;
    END;

    PROCEDURE logout_closes_session IS
        v_session NUMBER;
    BEGIN
        v_session := PKG_SECURITY.authenticate(c_email, 'x', NULL);
        ut.expect(PKG_SECURITY.is_session_valid(v_session)).to_be_true();
        PKG_SECURITY.logout(v_session);
        ut.expect(PKG_SECURITY.is_session_valid(v_session)).to_be_false();
    END;

    PROCEDURE encrypt_ssn_roundtrip_legacy IS
        v_enc VARCHAR2(400);
    BEGIN
        v_enc := PKG_SECURITY.encrypt_ssn('123-45-6789');
        ut.expect(PKG_SECURITY.decrypt_ssn(v_enc)).to_equal('123-45-6789');
    END;
END ut_pkg_security;
/
