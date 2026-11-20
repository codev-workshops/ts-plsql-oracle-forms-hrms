-- ============================================================================
-- P1: indexes
-- The Oracle DDL declares only PK/UK indexes. PostgreSQL does not index FK columns
-- automatically, so every FK column gets an index, plus the lookups used by the
-- legacy packages and views.
-- ============================================================================

-- TRG-004: email unique (case-insensitive) among active rows. Replaces the trigger check.
-- OQ-06 (closed 2026-11-20, user accepted the default): enforced on INSERT and UPDATE, whereas
-- TRG-004 checked INSERT only. Existing duplicates are reported by the P8 data migration.
CREATE UNIQUE INDEX uq_employees_email_active
    ON hrms.employees (lower(email))
    WHERE active_flag AND email IS NOT NULL;

-- Login identity resolution (D-06): lower(email) = lower(:username) AND status = 'ACTIVE'.
CREATE INDEX ix_employees_email_lower ON hrms.employees (lower(email));

-- FK indexes
CREATE INDEX ix_departments_parent ON hrms.departments (parent_dept_id);
CREATE INDEX ix_departments_manager ON hrms.departments (manager_emp_id);
CREATE INDEX ix_departments_location ON hrms.departments (location_code);
CREATE INDEX ix_job_titles_grade ON hrms.job_titles (grade_id);
CREATE INDEX ix_employees_dept ON hrms.employees (dept_id);
CREATE INDEX ix_employees_job ON hrms.employees (job_id);
CREATE INDEX ix_employees_manager ON hrms.employees (manager_emp_id);
CREATE INDEX ix_employees_location ON hrms.employees (location_code);
CREATE INDEX ix_employees_status ON hrms.employees (employment_status, active_flag);
CREATE INDEX ix_employee_history_emp ON hrms.employee_history (emp_id, effective_date);
CREATE INDEX ix_employee_dependents_emp ON hrms.employee_dependents (emp_id);
CREATE INDEX ix_emergency_contacts_emp ON hrms.emergency_contacts (emp_id);

CREATE INDEX ix_salary_records_emp ON hrms.salary_records (emp_id, effective_date);
CREATE INDEX ix_salary_records_approver ON hrms.salary_records (approved_by);
CREATE INDEX ix_emp_pay_elements_emp ON hrms.employee_pay_elements (emp_id);
CREATE INDEX ix_emp_pay_elements_element ON hrms.employee_pay_elements (element_id);
CREATE INDEX ix_pay_periods_dates ON hrms.pay_periods (pay_frequency, period_start_date);
CREATE INDEX ix_payroll_runs_period ON hrms.payroll_runs (period_id);
CREATE INDEX ix_payroll_runs_status ON hrms.payroll_runs (status);
CREATE INDEX ix_payroll_details_run_emp ON hrms.payroll_details (run_id, emp_id);
CREATE INDEX ix_payroll_details_emp ON hrms.payroll_details (emp_id);
CREATE INDEX ix_payroll_details_element ON hrms.payroll_details (element_id);
CREATE INDEX ix_tax_brackets_lookup ON hrms.tax_brackets (tax_year, filing_status, state_code);
CREATE INDEX ix_emp_bank_accounts_emp ON hrms.employee_bank_accounts (emp_id);

CREATE INDEX ix_leave_balances_type ON hrms.leave_balances (leave_type_id);
CREATE INDEX ix_leave_requests_emp_dates ON hrms.leave_requests (emp_id, start_date, end_date);
CREATE INDEX ix_leave_requests_type ON hrms.leave_requests (leave_type_id);
CREATE INDEX ix_leave_requests_approver_status ON hrms.leave_requests (approver_emp_id, status);
CREATE INDEX ix_leave_accrual_log_emp ON hrms.leave_accrual_log (emp_id, leave_type_id, accrual_date);
CREATE INDEX ix_leave_accrual_log_type ON hrms.leave_accrual_log (leave_type_id);
CREATE INDEX ix_holidays_date_location ON hrms.holidays (holiday_date, location_code);
CREATE INDEX ix_holidays_location ON hrms.holidays (location_code);

CREATE INDEX ix_performance_reviews_cycle ON hrms.performance_reviews (cycle_id);
CREATE INDEX ix_performance_reviews_emp ON hrms.performance_reviews (emp_id);
CREATE INDEX ix_performance_reviews_reviewer_status ON hrms.performance_reviews (reviewer_emp_id, status);
CREATE INDEX ix_performance_goals_review ON hrms.performance_goals (review_id);
CREATE INDEX ix_performance_goals_emp ON hrms.performance_goals (emp_id);

CREATE INDEX ix_audit_log_record ON hrms.audit_log (table_name, record_id);
CREATE INDEX ix_audit_log_changed_date ON hrms.audit_log (changed_date);
CREATE INDEX ix_notification_queue_pending ON hrms.notification_queue (priority, created_date) WHERE status = 'PENDING';
CREATE INDEX ix_notification_queue_recipient ON hrms.notification_queue (recipient_emp_id);
CREATE INDEX ix_user_sessions_emp ON hrms.user_sessions (emp_id);
CREATE INDEX ix_lookup_values_parent ON hrms.lookup_values (parent_lookup_id);
