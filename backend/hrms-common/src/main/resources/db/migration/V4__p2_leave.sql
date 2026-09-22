-- ============================================================================
-- V4__p2_leave.sql - Phase 2 Leave (CUTOVER_PLAN.md §6, COMPONENT_MAPPING.md §5,
-- contracts/p2-leave/openapi.yaml). leave_requests / leave_balances (with the
-- STORED available column and uk_leave_bal) / leave_accrual_log already exist
-- in the V1 baseline (translated 1:1 from schema/tables/03_leave_tables.sql).
-- This migration adds what leave-service needs on top of it:
--   * leave_accrual_log.accrual_type + the idempotency key LeaveAccrualJob relies
--     on: one ACCRUAL row per (emp, type, accrual_date), one CARRYOVER / EXPIRY
--     row per (emp, type, year) - contract runMonthlyAccrual / runCarryover /
--     expireCarryover. PostgreSQL-only column (TableGroup.PG_ONLY_COLUMNS).
--   * the lookup indexes behind the contracted list / overlap / approval queries;
--   * the VIEW_ALL action in the authority vocabulary and the role seeding of the
--     Authority schema (EXECUTIVE gets LEAVE:VIEW_ALL + LEAVE:ADMIN, MANAGER and
--     STAFF get neither - PKG_SECURITY.has_permission grants no extra leave
--     right below grade 8).
-- No triggers, no PL/pgSQL (MODERNIZATION_BLUEPRINT.md §9 decision A).
-- ============================================================================

alter table leave_accrual_log
    add column accrual_type varchar(10) not null default 'ACCRUAL';
alter table leave_accrual_log
    add constraint chk_lal_type check (accrual_type in ('ACCRUAL', 'CARRYOVER', 'EXPIRY'));
create unique index uk_leave_accrual_idem
    on leave_accrual_log (emp_id, leave_type_id, accrual_type, accrual_date);

create index ix_leave_req_emp_created on leave_requests (emp_id, created_date desc, request_id desc);
create index ix_leave_req_emp_dates on leave_requests (emp_id, start_date, end_date)
    where status in ('PENDING', 'APPROVED');
create index ix_leave_req_approver_status on leave_requests (approver_emp_id, status, created_date);
create index ix_leave_bal_expiry on leave_balances (carryover_expiry_dt)
    where carryover_from_prev > 0;

alter table role_permissions drop constraint chk_rp_authority;
alter table role_permissions add constraint chk_rp_authority
    check (authority ~ '^(PAYROLL|EMPLOYEE|LEAVE|ADMIN|REPORTS|PERFORMANCE):(VIEW|EDIT|APPROVE|CREATE|ADMIN|VIEW_ALL)$');

insert into role_permissions (role_id, authority) values (3, 'LEAVE:VIEW_ALL'), (3, 'LEAVE:ADMIN');
