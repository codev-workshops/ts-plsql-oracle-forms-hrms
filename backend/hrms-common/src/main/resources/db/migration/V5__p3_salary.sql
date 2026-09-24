-- ============================================================================
-- V5__p3_salary.sql - Phase 3 Employee salary-module additions. salary_records
-- is owned exclusively by salary-module (contracts/p3-employee/openapi.yaml).
-- PostgreSQL-only indexes and out-of-grade-band advisory state replace the
-- legacy salary trigger without triggers or PL/pgSQL.
-- ============================================================================

create unique index uk_salary_emp_active
    on salary_records (emp_id)
    where active_flag = 'Y';

create index ix_salary_emp_history
    on salary_records (emp_id, effective_date desc, salary_id desc);

alter table salary_records
    add column out_of_grade_band char(1) not null default 'N';
alter table salary_records
    add constraint chk_salary_oogb check (out_of_grade_band in ('Y', 'N'));
