-- ============================================================================
-- P8 data migration, step 1: staging schema (run as the migration owner after `flyway migrate`
-- with the production locations only). One TEXT table per legacy table, with the target's columns
-- in the target's order (generated columns excluded); the Oracle CSV export is \copy-ed into it.
-- The schema is not granted to the application role and is dropped after go-live sign-off.
-- See docs/modernization/cutover-runbook.md.
-- ============================================================================
CREATE SCHEMA legacy_stage;
REVOKE ALL ON SCHEMA legacy_stage FROM PUBLIC;

-- The 30 legacy tables in load order (parents first; departments.manager_emp_id is set after employees).
CREATE TABLE legacy_stage.legacy_tables (
    table_name  text PRIMARY KEY,
    load_order  integer NOT NULL UNIQUE
);
INSERT INTO legacy_stage.legacy_tables (table_name, load_order) VALUES
    ('locations', 1), ('job_grades', 2), ('job_titles', 3), ('departments', 4), ('employees', 5),
    ('employee_history', 6), ('employee_dependents', 7), ('emergency_contacts', 8), ('salary_records', 9),
    ('pay_elements', 10), ('employee_pay_elements', 11), ('pay_periods', 12), ('payroll_runs', 13),
    ('payroll_details', 14), ('tax_brackets', 15), ('employee_tax_info', 16), ('employee_bank_accounts', 17),
    ('leave_types', 18), ('leave_balances', 19), ('leave_requests', 20), ('leave_accrual_log', 21),
    ('holidays', 22), ('review_cycles', 23), ('performance_reviews', 24), ('performance_goals', 25),
    ('audit_log', 26), ('system_parameters', 27), ('notification_queue', 28), ('user_sessions', 29),
    ('lookup_values', 30);

CREATE TABLE legacy_stage.migration_issues (
    issue_id    bigserial PRIMARY KEY,
    severity    text NOT NULL CHECK (severity IN ('BLOCKING', 'WARN', 'INFO')),
    check_name  text NOT NULL,
    table_name  text,
    detail      text NOT NULL,
    row_count   bigint
);

-- P4 V200 unique key: legacy duplicate reviews are dropped and their goals re-pointed.
CREATE TABLE legacy_stage.review_dedupe (
    dropped_review_id bigint PRIMARY KEY,
    kept_review_id    bigint NOT NULL
);

-- OQ-P5-2: legacy SSN ciphertext (hard-coded key, DEF-11) is held here, not loaded into hrms.employees,
-- until the key-management decision; re-encryption with the external key is a go-live prerequisite.
CREATE TABLE legacy_stage.ssn_holding (
    emp_id        bigint PRIMARY KEY,
    ssn_encrypted text NOT NULL
);

-- The load's timestamp: NULL legacy values of NOT NULL timestamp columns are backfilled with it.
CREATE TABLE legacy_stage.load_run (
    loaded_at timestamptz NOT NULL
);

CREATE TABLE legacy_stage.load_log (
    table_name text PRIMARY KEY,
    rows_loaded bigint NOT NULL
);

DO $$
DECLARE
    t record;
    cols text;
BEGIN
    FOR t IN SELECT table_name FROM legacy_stage.legacy_tables ORDER BY load_order LOOP
        SELECT string_agg(format('%I text', a.attname), ', ' ORDER BY a.attnum) INTO cols
        FROM pg_attribute a
        WHERE a.attrelid = format('hrms.%I', t.table_name)::regclass
          AND a.attnum > 0 AND NOT a.attisdropped AND a.attgenerated = '';
        EXECUTE format('CREATE TABLE legacy_stage.%I (%s)', t.table_name, cols);
    END LOOP;
END
$$;
