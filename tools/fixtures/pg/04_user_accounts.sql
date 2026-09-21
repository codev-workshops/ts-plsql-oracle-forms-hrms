-- PostgreSQL-only login fixture (user_accounts / user_roles are Phase 0 additive tables,
-- V2__p0_auth_foundation.sql; the legacy Oracle seed has no counterpart - PKG_SECURITY does not
-- verify passwords, SEC-05). Hand-maintained; NOT produced by tools/fixtures/generate_fixtures.py.
--
-- Every account uses the password Welcome1! (BCrypt cost 12, the auth-service PasswordEncoder).
-- One account per role band so the Level-2 harness and the integration stack can log in
-- without a session-local seed:
--   user 1  emp 1  james.richardson  EXECUTIVE (grade 10)
--   user 2  emp 2  sarah.chen        STAFF     - the parallel-run seed user (ScenarioRegistry.USER)
--   user 3  emp 21 jennifer.park     MANAGER   (grade 6)
--   user 4  emp 11 david.martinez    STAFF     (grade 3)
--   user 5  emp 12 emily.johnson     STAFF     (grade 2), must_change_password = true
insert into user_accounts (user_id, emp_id, username, password_hash, must_change_password, created_by, created_date)
values (1, 1, 'james.richardson@company.com', '$2a$12$YSc6RRK0C4i3mZZXCcmIVOgzslTQDhKU3j1iWj3bufjVWi3/4ODYu', false, 'SEED', DATE '2024-01-01');
insert into user_accounts (user_id, emp_id, username, password_hash, must_change_password, created_by, created_date)
values (2, 2, 'sarah.chen@company.com', '$2a$12$YSc6RRK0C4i3mZZXCcmIVOgzslTQDhKU3j1iWj3bufjVWi3/4ODYu', false, 'SEED', DATE '2024-01-01');
insert into user_accounts (user_id, emp_id, username, password_hash, must_change_password, created_by, created_date)
values (3, 21, 'jennifer.park@company.com', '$2a$12$YSc6RRK0C4i3mZZXCcmIVOgzslTQDhKU3j1iWj3bufjVWi3/4ODYu', false, 'SEED', DATE '2024-01-01');
insert into user_accounts (user_id, emp_id, username, password_hash, must_change_password, created_by, created_date)
values (4, 11, 'david.martinez@company.com', '$2a$12$YSc6RRK0C4i3mZZXCcmIVOgzslTQDhKU3j1iWj3bufjVWi3/4ODYu', false, 'SEED', DATE '2024-01-01');
insert into user_accounts (user_id, emp_id, username, password_hash, must_change_password, created_by, created_date)
values (5, 12, 'emily.johnson@company.com', '$2a$12$YSc6RRK0C4i3mZZXCcmIVOgzslTQDhKU3j1iWj3bufjVWi3/4ODYu', true, 'SEED', DATE '2024-01-01');

insert into user_roles (user_id, role_id, granted_by, granted_date) values (1, 3, 'SEED', DATE '2024-01-01');
insert into user_roles (user_id, role_id, granted_by, granted_date) values (2, 1, 'SEED', DATE '2024-01-01');
insert into user_roles (user_id, role_id, granted_by, granted_date) values (3, 2, 'SEED', DATE '2024-01-01');
insert into user_roles (user_id, role_id, granted_by, granted_date) values (4, 1, 'SEED', DATE '2024-01-01');
insert into user_roles (user_id, role_id, granted_by, granted_date) values (5, 1, 'SEED', DATE '2024-01-01');
