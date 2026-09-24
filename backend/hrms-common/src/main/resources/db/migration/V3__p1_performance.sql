-- ============================================================================
-- V3__p1_performance.sql - Phase 1 Performance (CUTOVER_PLAN.md §5, COMPONENT_MAPPING.md §6)
-- review_cycles / performance_reviews / performance_goals already exist in the
-- V1 baseline (translated 1:1 from schema/tables/04_performance_tables.sql).
-- This migration adds what performance-service needs on top of it:
--   * the (cycle_id, emp_id) uniqueness that generate_reviews_for_cycle's legacy
--     DUP_VAL_ON_INDEX handler assumed but the Oracle DDL never had
--     (contracts/p1-performance/openapi.yaml, generateReviews);
--   * the lookup indexes behind the contracted list/dashboard queries;
--   * the PERFORMANCE module + ADMIN action in the authority vocabulary and the
--     role seeding of the Authority schema (EXECUTIVE all five, MANAGER VIEW).
-- No triggers, no PL/pgSQL (MODERNIZATION_BLUEPRINT.md §9 decision A).
-- ============================================================================

create unique index uk_perf_reviews_cycle_emp on performance_reviews (cycle_id, emp_id);
create index ix_perf_reviews_reviewer_cycle on performance_reviews (reviewer_emp_id, cycle_id);
create index ix_perf_reviews_emp on performance_reviews (emp_id);
create index ix_perf_goals_review on performance_goals (review_id);
create index ix_review_cycles_status_year on review_cycles (status, cycle_year desc);

alter table role_permissions drop constraint chk_rp_authority;
alter table role_permissions add constraint chk_rp_authority
    check (authority ~ '^(PAYROLL|EMPLOYEE|LEAVE|ADMIN|REPORTS|PERFORMANCE):(VIEW|EDIT|APPROVE|CREATE|ADMIN)$');

insert into role_permissions (role_id, authority)
select 3, 'PERFORMANCE:' || a
from unnest(array['VIEW','EDIT','APPROVE','CREATE','ADMIN']) as a;

insert into role_permissions (role_id, authority) values (2, 'PERFORMANCE:VIEW');
