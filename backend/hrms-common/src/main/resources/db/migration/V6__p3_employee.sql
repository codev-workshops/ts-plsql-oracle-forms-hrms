-- ============================================================================
-- V6__p3_employee.sql - Phase 3 employee-service additions (the "V5__employee"
-- migration of CUTOVER_PLAN.md §7.3; V5 was taken by the salary-module node).
-- employee-service exclusively owns employees, employee_history,
-- employee_dependents and emergency_contacts (contracts/p3-employee).
-- The TRG_EMPLOYEES rules (-20501..-20504) are Java service invariants; this
-- file only adds the PostgreSQL-native supporting state (no triggers/PL/pgSQL).
-- ============================================================================

-- Optimistic locking for PUT /api/employees/{id} (ETag / If-Match = version).
alter table employees
    add column version integer not null default 0;

-- Legacy PKG_EMPLOYEE.log_history wrote p_comments to EMPLOYEE_HISTORY.COMMENTS;
-- the P0 baseline lost the column - restore it for TERMINATION / TRANSFER rows.
alter table employee_history
    add column comments varchar(4000);

-- -20502 (TRG_EMP_BEFORE_INSERT): uniqueness is among *active* employees only,
-- case-insensitively. The P0 index enforced it on every row, which would block
-- re-using the e-mail of a terminated employee (legacy allowed it).
drop index if exists uk_employees_email_lower;
create unique index uk_employees_email_active
    on employees (upper(email))
    where active_flag = 'Y' and email is not null;

create index if not exists ix_employees_search on employees (upper(last_name), upper(first_name), emp_id);
create index if not exists ix_emp_history_emp on employee_history (emp_id, effective_date desc, hist_id desc);
create index if not exists ix_dependents_emp_active on employee_dependents (emp_id) where active_flag = 'Y';
create index if not exists ix_contacts_emp_active on emergency_contacts (emp_id, priority_order, contact_id)
    where active_flag = 'Y';
