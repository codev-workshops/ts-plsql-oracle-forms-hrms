-- Phase 5 – Reporting / Integration / reference-data ownership (contracts/p5-reporting-decommission).
-- No PL/pgSQL, no triggers: every audit / created_by column is written by the owning Java module.
-- reporting-module owns no table (read-only projections); admin-module takes over the reference
-- tables (no DDL needed); integration-module owns the two tables below; leave_jobs is the state of
-- the admin-triggered leave batch (owned by leave-module together with leave_accrual_log).

-- ---------------------------------------------------------------- integration_log (PostgreSQL only)
create sequence seq_integration_log start with 1 increment by 1 cache 1;

create table integration_log (
    log_id               bigint          not null,
    feed                 varchar(20)     not null,
    status               varchar(10)     not null,
    file_id              uuid,
    source_ref           varchar(100),
    record_count         integer,
    message              varchar(4000),
    created_by           varchar(100)    not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_integration_log primary key (log_id),
    constraint chk_il_feed check (feed in ('GL_JOURNAL', 'BENEFITS_FEED', 'TIME_ATTENDANCE')),
    constraint chk_il_status check (status in ('SUCCESS', 'FAILED', 'STAGED'))
);
create index ix_integration_log_feed on integration_log (feed, created_date desc);

-- ---------------------------------------------------------------- integration_files (feed metadata + object key)
create table integration_files (
    file_id              uuid            not null,
    feed                 varchar(20)     not null,
    file_name            varchar(200)    not null,
    status               varchar(10)     not null,
    size_bytes           bigint          not null,
    sha256               char(64)        not null,
    record_count         integer         not null,
    source_ref           varchar(100),
    storage_bucket       varchar(63)     not null,
    storage_key          varchar(500)    not null,
    content_type         varchar(60)     not null,
    message              varchar(4000),
    created_by           varchar(100)    not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_integration_files primary key (file_id),
    constraint uk_integration_files_key unique (storage_bucket, storage_key),
    constraint chk_if_feed check (feed in ('GL_JOURNAL', 'BENEFITS_FEED', 'TIME_ATTENDANCE')),
    constraint chk_if_status check (status in ('SUCCESS', 'FAILED', 'STAGED'))
);
create index ix_integration_files_feed on integration_files (feed, created_date desc);

-- ---------------------------------------------------------------- leave_jobs (accrual / carryover admin triggers)
create table leave_jobs (
    job_id               uuid            not null,
    job_type             varchar(10)     not null,
    job_key              varchar(20)     not null,
    status               varchar(10)     not null,
    processed            integer         default 0 not null,
    skipped              integer         default 0 not null,
    failed               integer         default 0 not null,
    started_at           timestamp(0)    not null,
    finished_at          timestamp(0),
    started_by           varchar(100)    not null,
    message              varchar(4000),
    constraint pk_leave_jobs primary key (job_id),
    constraint chk_lj_type check (job_type in ('ACCRUAL', 'CARRYOVER')),
    constraint chk_lj_status check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);
-- one RUNNING job per (type, key): -20702 is raised by the Java trigger, the index is the race guard
create unique index uk_leave_jobs_running on leave_jobs (job_type, job_key) where status = 'RUNNING';

-- ---------------------------------------------------------------- audit_log: admin writes (changed_by = jwt.username)
create index if not exists ix_audit_log_search on audit_log (changed_date desc, audit_id desc);
create index if not exists ix_audit_log_table_record on audit_log (table_name, record_id);
