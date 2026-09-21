CREATE OR REPLACE PACKAGE ut_pkg_employee AS
    -- %suite(PKG_EMPLOYEE characterization)
    -- %suitepath(hrms.golden)
    -- %rollback(manual)

    -- %test(generate_emp_number yields EMP-000100 on the frozen seed)
    PROCEDURE generate_emp_number_after_seed;

    -- %test(create_employee assigns the next number and an ACTIVE row)
    PROCEDURE create_employee_happy_path;

    -- %test(create_employee raises -20001 on missing mandatory data)
    -- %throws(-20001)
    PROCEDURE create_employee_missing_name;

    -- %test(create_employee raises -20003 when the e-mail already exists)
    -- %throws(-20003)
    PROCEDURE create_employee_duplicate_email;

    -- %test(BUG-01 legacy: MAX()+1 numbering is not concurrency-safe - documented, single-session run only)
    -- %tags(legacy_defect,BUG-01)
    PROCEDURE emp_number_is_max_plus_one_legacy;

    -- %test(terminate then rehire keeps the same EMP_NUMBER)
    PROCEDURE terminate_and_rehire_keeps_number;
END ut_pkg_employee;
/
