-- V11__p3_salary_change_pct_precision.sql - Phase 3 salary-module (contracts/p3-employee).

alter table salary_records alter column change_pct type numeric(16,2);
