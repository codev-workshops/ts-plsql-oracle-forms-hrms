CREATE OR REPLACE PACKAGE BODY ut_pkg_employee AS
    PROCEDURE generate_emp_number_after_seed IS
    BEGIN
        ut.expect(PKG_EMPLOYEE.generate_emp_number()).to_equal('EMP-000100');
    END;

    PROCEDURE create_employee_happy_path IS
        v_id NUMBER; v_num VARCHAR2(20); v_status VARCHAR2(20);
    BEGIN
        v_id := PKG_EMPLOYEE.create_employee('Golden', 'Fixture', TRUNC(SYSDATE), 1, 1,
                                             NULL, NULL, 'FULL_TIME', 60000, 'golden.fixture@acme-corp.com', 'UT');
        SELECT EMP_NUMBER, EMPLOYMENT_STATUS INTO v_num, v_status FROM EMPLOYEES WHERE EMP_ID = v_id;
        ut.expect(v_num).to_equal('EMP-000100');
        ut.expect(v_status).to_equal('ACTIVE');
    END;

    PROCEDURE create_employee_missing_name IS
        v_id NUMBER;
    BEGIN
        v_id := PKG_EMPLOYEE.create_employee(NULL, 'Fixture', TRUNC(SYSDATE), 1, 1);
    END;

    PROCEDURE create_employee_duplicate_email IS
        v_id NUMBER;
    BEGIN
        v_id := PKG_EMPLOYEE.create_employee('Dup', 'Email', TRUNC(SYSDATE), 1, 1,
                                             NULL, NULL, 'FULL_TIME', 60000, 'sarah.chen@acme-corp.com');
    END;

    PROCEDURE emp_number_is_max_plus_one_legacy IS
        v_max VARCHAR2(20);
    BEGIN
        SELECT MAX(EMP_NUMBER) INTO v_max FROM EMPLOYEES;
        ut.expect(PKG_EMPLOYEE.generate_emp_number())
          .to_equal('EMP-' || LPAD(TO_NUMBER(SUBSTR(v_max, 5)) + 1, 6, '0'));
    END;

    PROCEDURE terminate_and_rehire_keeps_number IS
        v_id NUMBER; v_before VARCHAR2(20); v_after VARCHAR2(20);
    BEGIN
        v_id := PKG_EMPLOYEE.create_employee('Re', 'Hire', TRUNC(SYSDATE) - 400, 1, 1,
                                             NULL, NULL, 'FULL_TIME', 50000, 'rehire@acme-corp.com');
        SELECT EMP_NUMBER INTO v_before FROM EMPLOYEES WHERE EMP_ID = v_id;
        PKG_EMPLOYEE.terminate_employee(v_id, TRUNC(SYSDATE) - 30, 'RESIGNED', 'UT');
        PKG_EMPLOYEE.rehire_employee(v_id, TRUNC(SYSDATE), 1, 1, 50000, 'UT');
        SELECT EMP_NUMBER INTO v_after FROM EMPLOYEES WHERE EMP_ID = v_id;
        ut.expect(v_after).to_equal(v_before);
    END;
END ut_pkg_employee;
/
