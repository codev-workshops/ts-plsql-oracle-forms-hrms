-- ============================================================================
-- V2__p0_auth_foundation.sql - Phase 0 additions (CUTOVER_PLAN.md §4.2 item 0.6)
-- PostgreSQL-only tables owned by the auth module: user_accounts, roles,
-- role_permissions, user_roles, refresh_tokens (hrms_refresh cookie backing),
-- error_log (HRMS_COMMON.pll handle_error replacement) and the unique index on
-- lower(employees.email) that closes SEC-10 (duplicate e-mail).
-- ============================================================================

create sequence seq_user_account  start with 1 increment by 1 cache 1;
create sequence seq_role          start with 1 increment by 1 cache 1;
create sequence seq_error_log     start with 1 increment by 1 cache 1;

-- One login account per employee. password_hash is BCrypt ($2a$/$2b$) or Argon2
-- ($argon2id$); PKG_SECURITY.hash_password (MD5) is never reproduced (SEC-01).
create table user_accounts (
    user_id                 bigint          not null,
    emp_id                  bigint          not null,
    username                varchar(100)    not null,
    password_hash           varchar(200)    not null,
    password_changed_at     timestamp(0),
    must_change_password    boolean         default false not null,
    failed_attempts         integer         default 0 not null,
    locked_until            timestamp(0),
    status                  varchar(20)     default 'ACTIVE' not null,
    created_by              varchar(30)     not null,
    created_date            timestamp(0)    default current_timestamp not null,
    modified_by             varchar(30),
    modified_date           timestamp(0),
    constraint pk_user_accounts primary key (user_id),
    constraint uk_user_accounts_emp unique (emp_id),
    constraint fk_ua_emp foreign key (emp_id) references employees (emp_id),
    constraint chk_ua_status check (status in ('ACTIVE', 'DISABLED'))
);
create unique index uk_user_accounts_username on user_accounts (lower(username));

-- Roles reproduce the PKG_SECURITY.has_permission grade bands
-- (TEST_STRATEGY.md §5 row 0 truth table). min_grade/max_grade drive seeding.
create table roles (
    role_id       integer      not null,
    role_code     varchar(30)  not null,
    role_name     varchar(100) not null,
    min_grade     integer      not null,
    max_grade     integer      not null,
    created_by    varchar(30)  not null,
    created_date  timestamp(0) default current_timestamp not null,
    constraint pk_roles primary key (role_id),
    constraint uk_roles_code unique (role_code),
    constraint chk_roles_grade check (max_grade >= min_grade)
);

-- authority = MODULE:ACTION (openapi.yaml Authority pattern)
create table role_permissions (
    role_id    integer     not null,
    authority  varchar(40) not null,
    constraint pk_role_permissions primary key (role_id, authority),
    constraint fk_rp_role foreign key (role_id) references roles (role_id),
    constraint chk_rp_authority check (authority ~ '^(PAYROLL|EMPLOYEE|LEAVE|ADMIN|REPORTS):(VIEW|EDIT|APPROVE|CREATE)$')
);

create table user_roles (
    user_id      bigint       not null,
    role_id      integer      not null,
    granted_by   varchar(30)  not null,
    granted_date timestamp(0) default current_timestamp not null,
    constraint pk_user_roles primary key (user_id, role_id),
    constraint fk_ur_user foreign key (user_id) references user_accounts (user_id),
    constraint fk_ur_role foreign key (role_id) references roles (role_id)
);

-- Opaque refresh tokens (hrms_refresh cookie). Only the SHA-256 of the token is
-- stored; rotation revokes the previous row (replaced_by) so reuse is detected.
create table refresh_tokens (
    token_hash    char(64)     not null,
    user_id       bigint       not null,
    session_id    bigint       not null,
    jti           varchar(36)  not null,
    issued_at     timestamp(0) not null,
    expires_at    timestamp(0) not null,
    revoked_at    timestamp(0),
    replaced_by   char(64),
    constraint pk_refresh_tokens primary key (token_hash),
    constraint fk_rt_user foreign key (user_id) references user_accounts (user_id),
    constraint fk_rt_session foreign key (session_id) references user_sessions (session_id)
);
create index ix_refresh_tokens_session on refresh_tokens (session_id);

-- Revoked access-token ids (logout) - checked by the JWT filter until exp.
create table revoked_jti (
    jti         varchar(36)  not null,
    expires_at  timestamp(0) not null,
    revoked_at  timestamp(0) default current_timestamp not null,
    constraint pk_revoked_jti primary key (jti)
);

-- HRMS_COMMON.pll handle_error -> ErrorLogService (REQUIRES_NEW).
-- error_code is an INTEGER for the legacy -20xxx numbers (null for framework
-- codes, which go to error_key) so the reconciliation join with Oracle works.
create table error_log (
    error_id       bigint        not null,
    trace_id       varchar(64)   not null,
    error_code     integer,
    error_key      varchar(64)   not null,
    http_status    integer       not null,
    message        varchar(4000) not null,
    detail         text,
    request_path   varchar(500),
    username       varchar(100),
    created_date   timestamp(0)  default current_timestamp not null,
    constraint pk_error_log primary key (error_id)
);
create index ix_error_log_trace on error_log (trace_id);

-- SEC-10: duplicate e-mails can no longer exist on PostgreSQL.
create unique index uk_employees_email_lower on employees (lower(email)) where email is not null;

-- Additive column mirroring the Oracle write-through (CUTOVER_PLAN.md §4.2 item 0.6)
alter table user_sessions add column jwt_jti varchar(36);
create index ix_user_sessions_jti on user_sessions (jwt_jti);

-- ------------------------------------------------ role truth table seeding
-- PKG_SECURITY.has_permission:
--   grade >= 8            -> every module x every action
--   5 <= grade <= 7       -> VIEW on every module, plus LEAVE:CREATE
--   grade < 5             -> EMPLOYEE:VIEW, LEAVE:VIEW, LEAVE:CREATE
insert into roles (role_id, role_code, role_name, min_grade, max_grade, created_by) values
    (1, 'STAFF',      'Staff (grade 1-4)',            1, 4,   'SYSTEM'),
    (2, 'MANAGER',    'Manager (grade 5-7)',          5, 7,   'SYSTEM'),
    (3, 'EXECUTIVE',  'Senior management (grade 8+)', 8, 999, 'SYSTEM');

insert into role_permissions (role_id, authority)
select 3, m || ':' || a
from unnest(array['PAYROLL','EMPLOYEE','LEAVE','ADMIN','REPORTS']) as m
cross join unnest(array['VIEW','EDIT','APPROVE','CREATE']) as a;

insert into role_permissions (role_id, authority)
select 2, m || ':VIEW' from unnest(array['PAYROLL','EMPLOYEE','LEAVE','ADMIN','REPORTS']) as m;
insert into role_permissions (role_id, authority) values (2, 'LEAVE:CREATE');

insert into role_permissions (role_id, authority) values
    (1, 'EMPLOYEE:VIEW'), (1, 'LEAVE:VIEW'), (1, 'LEAVE:CREATE');
