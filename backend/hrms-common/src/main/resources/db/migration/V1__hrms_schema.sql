-- ============================================================================
-- V1__hrms_schema.sql - PostgreSQL baseline translated from schema/ (Oracle 19c)
-- MODERNIZATION_BLUEPRINT.md §10 rules:
--   * unquoted lower-case identifiers
--   * NUMBER(p)      -> integer / bigint / numeric(p)
--   * NUMBER(p,s)    -> numeric(p,s)
--   * VARCHAR2 / CHAR(1) flags kept as varchar / char(1) ('Y'/'N' comparisons)
--   * DATE with time -> timestamp(0); date-only semantics -> date
--   * CLOB -> text, BLOB -> bytea
--   * SEQ_* -> sequences, CACHE 1 (Oracle NOCACHE / CACHE n)
--   * LEAVE_BALANCES.AVAILABLE -> GENERATED ALWAYS AS (...) STORED
--   * NO triggers, NO PL/pgSQL, NO views used by application code
--     (the Oracle VW_* views remain the reconciliation oracle; PostgreSQL
--      equivalents live in tests/reconciliation/pg/).
-- ============================================================================

-- ---------------------------------------------------------------- sequences
create sequence seq_department       start with 1000  increment by 1 cache 1;
create sequence seq_location         start with 1     increment by 1 cache 1;
create sequence seq_job_grade        start with 100   increment by 1 cache 1;
create sequence seq_job_title        start with 1000  increment by 1 cache 1;
create sequence seq_employee         start with 10000 increment by 1 cache 1;
create sequence seq_emp_history      start with 1     increment by 1 cache 1;
create sequence seq_dependent        start with 1     increment by 1 cache 1;
create sequence seq_emergency_contact start with 1    increment by 1 cache 1;
create sequence seq_emp_number       start with 1000  increment by 1 cache 1;
create sequence seq_salary           start with 1     increment by 1 cache 1;
create sequence seq_pay_element      start with 1     increment by 1 cache 1;
create sequence seq_emp_pay_element  start with 1     increment by 1 cache 1;
create sequence seq_pay_period       start with 1     increment by 1 cache 1;
create sequence seq_payroll_run      start with 1     increment by 1 cache 1;
create sequence seq_payroll_detail   start with 1     increment by 1 cache 1;
create sequence seq_tax_bracket      start with 1     increment by 1 cache 1;
create sequence seq_leave_type       start with 1     increment by 1 cache 1;
create sequence seq_leave_balance    start with 1     increment by 1 cache 1;
create sequence seq_leave_request    start with 1     increment by 1 cache 1;
create sequence seq_leave_accrual    start with 1     increment by 1 cache 1;
create sequence seq_holiday          start with 1     increment by 1 cache 1;
create sequence seq_review_cycle     start with 1     increment by 1 cache 1;
create sequence seq_perf_review      start with 1     increment by 1 cache 1;
create sequence seq_perf_goal        start with 1     increment by 1 cache 1;
create sequence seq_audit            start with 1     increment by 1 cache 1;
create sequence seq_notification     start with 1     increment by 1 cache 1;
create sequence seq_user_session     start with 1     increment by 1 cache 1;
create sequence seq_system_param     start with 1     increment by 1 cache 1;
create sequence seq_lookup           start with 1     increment by 1 cache 1;

-- ------------------------------------------------------------- core tables
create table locations (
    location_code        varchar(10)     not null,
    location_name        varchar(100)    not null,
    address_line1        varchar(200),
    address_line2        varchar(200),
    city                 varchar(100),
    state_province       varchar(100),
    postal_code          varchar(20),
    country_code         varchar(3),
    phone_number         varchar(30),
    timezone             varchar(50)     default 'America/New_York',
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_locations primary key (location_code)
);

create table departments (
    dept_id              bigint          not null,
    dept_code            varchar(20)     not null,
    dept_name            varchar(100)    not null,
    parent_dept_id       bigint,
    cost_center          varchar(20),
    manager_emp_id       bigint,
    location_code        varchar(10),
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_departments primary key (dept_id),
    constraint uk_dept_code unique (dept_code),
    constraint chk_dept_active check (active_flag in ('Y', 'N'))
);

create table job_grades (
    grade_id             integer         not null,
    grade_code           varchar(10)     not null,
    grade_name           varchar(50)     not null,
    min_salary           numeric(12,2)   not null,
    max_salary           numeric(12,2)   not null,
    overtime_eligible    char(1)         default 'N',
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_job_grades primary key (grade_id),
    constraint uk_grade_code unique (grade_code),
    constraint chk_salary_range check (max_salary >= min_salary)
);

create table job_titles (
    job_id               bigint          not null,
    job_code             varchar(20)     not null,
    job_title            varchar(100)    not null,
    job_family           varchar(50),
    grade_id             integer         not null,
    eeo_category         varchar(10),
    flsa_status          varchar(10)     default 'EXEMPT',
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_job_titles primary key (job_id),
    constraint uk_job_code unique (job_code),
    constraint fk_job_grade foreign key (grade_id) references job_grades (grade_id)
);

create table employees (
    emp_id               bigint          not null,
    emp_number           varchar(20)     not null,
    first_name           varchar(50)     not null,
    middle_name          varchar(50),
    last_name            varchar(50)     not null,
    date_of_birth        date,
    gender               char(1),
    marital_status       varchar(10),
    nationality          varchar(50),
    ssn_encrypted        varchar(200),
    email                varchar(100),
    phone_work           varchar(30),
    phone_mobile         varchar(30),
    address_line1        varchar(200),
    address_line2        varchar(200),
    city                 varchar(100),
    state_province       varchar(100),
    postal_code          varchar(20),
    country_code         varchar(3),
    hire_date            date            not null,
    termination_date     date,
    termination_reason   varchar(50),
    dept_id              bigint          not null,
    job_id               bigint          not null,
    manager_emp_id       bigint,
    location_code        varchar(10),
    employment_type      varchar(20)     default 'FULL_TIME',
    employment_status    varchar(20)     default 'ACTIVE',
    photo_blob           bytea,
    notes                text,
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_employees primary key (emp_id),
    constraint uk_emp_number unique (emp_number),
    constraint fk_emp_dept foreign key (dept_id) references departments (dept_id),
    constraint fk_emp_job foreign key (job_id) references job_titles (job_id),
    constraint fk_emp_manager foreign key (manager_emp_id) references employees (emp_id),
    constraint fk_emp_location foreign key (location_code) references locations (location_code),
    constraint chk_emp_status check (employment_status in ('ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'TERMINATED')),
    constraint chk_emp_type check (employment_type in ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN')),
    constraint chk_emp_gender check (gender in ('M', 'F', 'O'))
);

alter table departments
    add constraint fk_dept_parent foreign key (parent_dept_id) references departments (dept_id),
    add constraint fk_dept_manager foreign key (manager_emp_id) references employees (emp_id),
    add constraint fk_dept_location foreign key (location_code) references locations (location_code);

create table employee_history (
    hist_id              bigint          not null,
    emp_id               bigint          not null,
    change_type          varchar(30)     not null,
    effective_date       date            not null,
    old_dept_id          bigint,
    new_dept_id          bigint,
    old_job_id           bigint,
    new_job_id           bigint,
    old_manager_id       bigint,
    new_manager_id       bigint,
    old_salary           numeric(12,2),
    new_salary           numeric(12,2),
    old_location         varchar(10),
    new_location         varchar(10),
    reason_code          varchar(30),
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_emp_history primary key (hist_id),
    constraint fk_hist_emp foreign key (emp_id) references employees (emp_id),
    constraint chk_change_type check (change_type in (
        'HIRE', 'TRANSFER', 'PROMOTION', 'DEMOTION', 'SALARY_CHANGE',
        'TERMINATION', 'REHIRE', 'LEAVE_START', 'LEAVE_END', 'STATUS_CHANGE'))
);

create table employee_dependents (
    dependent_id         bigint          not null,
    emp_id               bigint          not null,
    first_name           varchar(50)     not null,
    last_name            varchar(50)     not null,
    relationship         varchar(20)     not null,
    date_of_birth        date,
    ssn_encrypted        varchar(200),
    benefits_enrolled    char(1)         default 'N',
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_emp_dependents primary key (dependent_id),
    constraint fk_dep_emp foreign key (emp_id) references employees (emp_id),
    constraint chk_relationship check (relationship in ('SPOUSE', 'CHILD', 'PARENT', 'DOMESTIC_PARTNER', 'OTHER'))
);

create table emergency_contacts (
    contact_id           bigint          not null,
    emp_id               bigint          not null,
    contact_name         varchar(100)    not null,
    relationship         varchar(30),
    phone_primary        varchar(30)     not null,
    phone_secondary      varchar(30),
    email                varchar(100),
    priority_order       smallint        default 1,
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_emergency_contacts primary key (contact_id),
    constraint fk_ec_emp foreign key (emp_id) references employees (emp_id)
);

-- ---------------------------------------------------------- payroll tables
create table salary_records (
    salary_id            bigint          not null,
    emp_id               bigint          not null,
    effective_date       date            not null,
    end_date             date,
    base_salary          numeric(12,2)   not null,
    currency_code        varchar(3)      default 'USD',
    pay_frequency        varchar(20)     default 'MONTHLY',
    salary_basis         varchar(20)     default 'ANNUAL',
    change_reason        varchar(50),
    change_pct           numeric(5,2),
    approved_by          bigint,
    approval_date        timestamp(0),
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_salary_records primary key (salary_id),
    constraint fk_sal_emp foreign key (emp_id) references employees (emp_id),
    constraint chk_pay_freq check (pay_frequency in ('WEEKLY', 'BIWEEKLY', 'SEMIMONTHLY', 'MONTHLY')),
    constraint chk_sal_basis check (salary_basis in ('ANNUAL', 'HOURLY'))
);

create table pay_elements (
    element_id           bigint          not null,
    element_code         varchar(30)     not null,
    element_name         varchar(100)    not null,
    element_type         varchar(20)     not null,
    calculation_type     varchar(20)     not null,
    default_amount       numeric(12,2),
    default_percentage   numeric(5,2),
    taxable_flag         char(1)         default 'Y',
    pretax_flag          char(1)         default 'N',
    employer_paid        char(1)         default 'N',
    gl_account_code      varchar(30),
    priority_order       integer         default 100,
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_pay_elements primary key (element_id),
    constraint uk_pay_elem_code unique (element_code),
    constraint chk_elem_type check (element_type in ('EARNING', 'DEDUCTION', 'TAX', 'BENEFIT', 'REIMBURSEMENT')),
    constraint chk_calc_type check (calculation_type in ('FLAT', 'PERCENTAGE', 'HOURS', 'FORMULA'))
);

create table employee_pay_elements (
    emp_element_id       bigint          not null,
    emp_id               bigint          not null,
    element_id           bigint          not null,
    effective_date       date            not null,
    end_date             date,
    amount               numeric(12,2),
    percentage           numeric(5,2),
    override_amount      numeric(12,2),
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_emp_pay_elements primary key (emp_element_id),
    constraint fk_epe_emp foreign key (emp_id) references employees (emp_id),
    constraint fk_epe_element foreign key (element_id) references pay_elements (element_id)
);

create table pay_periods (
    period_id            bigint          not null,
    period_name          varchar(50)     not null,
    pay_frequency        varchar(20)     not null,
    period_start_date    date            not null,
    period_end_date      date            not null,
    pay_date             date            not null,
    status               varchar(20)     default 'OPEN',
    closed_by            varchar(30),
    closed_date          timestamp(0),
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_pay_periods primary key (period_id),
    constraint chk_period_status check (status in ('OPEN', 'PROCESSING', 'CLOSED', 'REVERSED'))
);

create table payroll_runs (
    run_id               bigint          not null,
    period_id            bigint          not null,
    run_type             varchar(20)     default 'REGULAR',
    run_date             timestamp(0)    not null,
    status               varchar(20)     default 'PENDING',
    total_gross          numeric(15,2),
    total_deductions     numeric(15,2),
    total_net            numeric(15,2),
    total_employer_cost  numeric(15,2),
    employee_count       bigint,
    error_count          bigint          default 0,
    submitted_by         varchar(30),
    submitted_date       timestamp(0),
    approved_by          varchar(30),
    approved_date        timestamp(0),
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_payroll_runs primary key (run_id),
    constraint fk_pr_period foreign key (period_id) references pay_periods (period_id),
    constraint chk_run_type check (run_type in ('REGULAR', 'SUPPLEMENTAL', 'BONUS', 'FINAL')),
    constraint chk_run_status check (status in ('PENDING', 'CALCULATING', 'CALCULATED', 'APPROVED', 'PAID', 'REVERSED', 'ERROR'))
);

create table payroll_details (
    detail_id            bigint          not null,
    run_id               bigint          not null,
    emp_id               bigint          not null,
    element_id           bigint          not null,
    element_type         varchar(20)     not null,
    hours_worked         numeric(6,2),
    rate                 numeric(12,4),
    amount               numeric(12,2)   not null,
    ytd_amount           numeric(15,2),
    status               varchar(20)     default 'CALCULATED',
    error_message        varchar(4000),
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_payroll_details primary key (detail_id),
    constraint fk_pd_run foreign key (run_id) references payroll_runs (run_id),
    constraint fk_pd_emp foreign key (emp_id) references employees (emp_id),
    constraint fk_pd_element foreign key (element_id) references pay_elements (element_id)
);

create table tax_brackets (
    bracket_id           bigint          not null,
    tax_year             smallint        not null,
    filing_status        varchar(30)     not null,
    bracket_min          numeric(12,2)   not null,
    bracket_max          numeric(12,2),
    tax_rate             numeric(5,4)    not null,
    base_tax             numeric(12,2)   default 0,
    state_code           varchar(3),
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_tax_brackets primary key (bracket_id),
    constraint chk_filing_status check (filing_status in ('SINGLE', 'MARRIED_JOINT', 'MARRIED_SEPARATE', 'HEAD_OF_HOUSEHOLD'))
);

create table employee_tax_info (
    tax_info_id          bigint          not null,
    emp_id               bigint          not null,
    tax_year             smallint        not null,
    filing_status        varchar(30)     not null,
    federal_allowances   smallint        default 0,
    state_allowances     smallint        default 0,
    additional_fed_wh    numeric(12,2)   default 0,
    additional_state_wh  numeric(12,2)   default 0,
    exempt_flag          char(1)         default 'N',
    state_code           varchar(3),
    w4_received_date     date,
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_emp_tax_info primary key (tax_info_id),
    constraint fk_eti_emp foreign key (emp_id) references employees (emp_id),
    constraint uk_emp_tax_year unique (emp_id, tax_year)
);

create table employee_bank_accounts (
    bank_acct_id         bigint          not null,
    emp_id               bigint          not null,
    bank_name            varchar(100),
    routing_number       varchar(20)     not null,
    account_number_enc   varchar(200)    not null,
    account_type         varchar(20)     default 'CHECKING',
    deposit_type         varchar(20)     default 'FULL',
    deposit_amount       numeric(12,2),
    deposit_percentage   numeric(5,2),
    priority_order       smallint        default 1,
    prenote_sent         char(1)         default 'N',
    prenote_date         date,
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_emp_bank_accts primary key (bank_acct_id),
    constraint fk_ba_emp foreign key (emp_id) references employees (emp_id),
    constraint chk_acct_type check (account_type in ('CHECKING', 'SAVINGS')),
    constraint chk_deposit_type check (deposit_type in ('FULL', 'PARTIAL_AMOUNT', 'PARTIAL_PERCENT', 'REMAINDER'))
);

-- ------------------------------------------------------------ leave tables
create table leave_types (
    leave_type_id        integer         not null,
    leave_type_code      varchar(20)     not null,
    leave_type_name      varchar(50)     not null,
    paid_flag            char(1)         default 'Y',
    accrual_flag         char(1)         default 'Y',
    accrual_rate         numeric(6,2),
    accrual_frequency    varchar(20),
    max_balance          numeric(6,2),
    carryover_max        numeric(6,2),
    carryover_expiry     smallint,
    min_tenure_days      integer         default 0,
    requires_approval    char(1)         default 'Y',
    requires_document    char(1)         default 'N',
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_leave_types primary key (leave_type_id),
    constraint uk_leave_type_code unique (leave_type_code),
    constraint chk_accrual_freq check (accrual_frequency is null or accrual_frequency in ('MONTHLY', 'BIWEEKLY', 'ANNUAL'))
);

create table leave_balances (
    balance_id           bigint          not null,
    emp_id               bigint          not null,
    leave_type_id        integer         not null,
    calendar_year        smallint        not null,
    opening_balance      numeric(6,2)    default 0,
    accrued              numeric(6,2)    default 0,
    used                 numeric(6,2)    default 0,
    adjustment           numeric(6,2)    default 0,
    pending              numeric(6,2)    default 0,
    available            numeric(6,2)    generated always as (opening_balance + accrued - used + adjustment - pending) stored,
    carryover_from_prev  numeric(6,2)    default 0,
    carryover_expiry_dt  date,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_leave_balances primary key (balance_id),
    constraint fk_lb_emp foreign key (emp_id) references employees (emp_id),
    constraint fk_lb_type foreign key (leave_type_id) references leave_types (leave_type_id),
    constraint uk_leave_bal unique (emp_id, leave_type_id, calendar_year)
);

create table leave_requests (
    request_id           bigint          not null,
    emp_id               bigint          not null,
    leave_type_id        integer         not null,
    start_date           date            not null,
    end_date             date            not null,
    total_days           numeric(5,1)    not null,
    half_day_flag        char(1)         default 'N',
    half_day_period      varchar(10),
    status               varchar(20)     default 'PENDING',
    reason               varchar(4000),
    supporting_doc_path  varchar(500),
    approver_emp_id      bigint,
    approval_date        timestamp(0),
    approval_comments    varchar(4000),
    cancel_reason        varchar(4000),
    cancelled_date       timestamp(0),
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_leave_requests primary key (request_id),
    constraint fk_lr_emp foreign key (emp_id) references employees (emp_id),
    constraint fk_lr_type foreign key (leave_type_id) references leave_types (leave_type_id),
    constraint fk_lr_approver foreign key (approver_emp_id) references employees (emp_id),
    constraint chk_lr_status check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'TAKEN')),
    constraint chk_lr_dates check (end_date >= start_date),
    constraint chk_half_day check (half_day_period is null or half_day_period in ('AM', 'PM'))
);

create table leave_accrual_log (
    accrual_id           bigint          not null,
    emp_id               bigint          not null,
    leave_type_id        integer         not null,
    accrual_date         date            not null,
    accrual_amount       numeric(6,2)    not null,
    balance_after        numeric(6,2),
    run_id               bigint,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_leave_accrual_log primary key (accrual_id),
    constraint fk_lal_emp foreign key (emp_id) references employees (emp_id),
    constraint fk_lal_type foreign key (leave_type_id) references leave_types (leave_type_id)
);

create table holidays (
    holiday_id           integer         not null,
    holiday_date         date            not null,
    holiday_name         varchar(100)    not null,
    location_code        varchar(10),
    floating_flag        char(1)         default 'N',
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_holidays primary key (holiday_id)
);

-- ------------------------------------------------------ performance tables
create table review_cycles (
    cycle_id             bigint          not null,
    cycle_name           varchar(100)    not null,
    cycle_year           smallint        not null,
    start_date           date            not null,
    end_date             date            not null,
    self_review_due      date,
    manager_review_due   date,
    calibration_due      date,
    status               varchar(20)     default 'DRAFT',
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_review_cycles primary key (cycle_id),
    constraint chk_cycle_status check (status in ('DRAFT', 'OPEN', 'IN_PROGRESS', 'CALIBRATION', 'CLOSED'))
);

create table performance_reviews (
    review_id            bigint          not null,
    cycle_id             bigint          not null,
    emp_id               bigint          not null,
    reviewer_emp_id      bigint          not null,
    review_type          varchar(20)     default 'ANNUAL',
    status               varchar(20)     default 'NOT_STARTED',
    overall_rating       numeric(2,1),
    rating_label         varchar(50),
    self_assessment      text,
    manager_assessment   text,
    strengths            text,
    areas_for_improvement text,
    development_plan     text,
    employee_comments    text,
    employee_ack_date    timestamp(0),
    calibrated_rating    numeric(2,1),
    calibration_notes    varchar(4000),
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_performance_reviews primary key (review_id),
    constraint fk_pr_cycle foreign key (cycle_id) references review_cycles (cycle_id),
    constraint fk_pr_emp foreign key (emp_id) references employees (emp_id),
    constraint fk_pr_reviewer foreign key (reviewer_emp_id) references employees (emp_id),
    constraint chk_review_status check (status in ('NOT_STARTED', 'SELF_REVIEW', 'MANAGER_REVIEW', 'MEETING_SCHEDULED', 'COMPLETED', 'ACKNOWLEDGED')),
    constraint chk_rating_range check (overall_rating between 1.0 and 5.0)
);

create table performance_goals (
    goal_id              bigint          not null,
    review_id            bigint          not null,
    emp_id               bigint          not null,
    goal_title           varchar(200)    not null,
    goal_description     text,
    goal_category        varchar(30),
    weight_pct           numeric(5,2)    default 0,
    target_date          date,
    status               varchar(20)     default 'NOT_STARTED',
    progress_pct         numeric(5,2)    default 0,
    self_rating          numeric(2,1),
    manager_rating       numeric(2,1),
    comments             text,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_perf_goals primary key (goal_id),
    constraint fk_pg_review foreign key (review_id) references performance_reviews (review_id),
    constraint fk_pg_emp foreign key (emp_id) references employees (emp_id),
    constraint chk_goal_status check (status in ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED', 'DEFERRED', 'CANCELLED')),
    constraint chk_goal_category check (goal_category in ('BUSINESS', 'DEVELOPMENT', 'LEADERSHIP', 'INNOVATION', 'COMPLIANCE'))
);

-- ----------------------------------------------------- cross-cutting tables
create table audit_log (
    audit_id             bigint          not null,
    table_name           varchar(60)     not null,
    record_id            bigint          not null,
    action_type          varchar(20)     not null,
    old_values           text,
    new_values           text,
    changed_by           varchar(100)    not null,
    changed_date         timestamp(0)    default current_timestamp not null,
    ip_address           varchar(50),
    session_id           varchar(100),
    constraint pk_audit_log primary key (audit_id),
    -- CUTOVER_PLAN.md §4.2 item 0.6 adds STATUS_CHANGE; the contract's login/logout
    -- audit rows (openapi.yaml /api/auth/login) add LOGIN / LOGOUT.
    constraint chk_audit_action check (action_type in ('INSERT', 'UPDATE', 'DELETE', 'STATUS_CHANGE', 'LOGIN', 'LOGOUT'))
);

create table system_parameters (
    param_id             integer         not null,
    param_group          varchar(50)     not null,
    param_code           varchar(50)     not null,
    param_value          varchar(4000)   not null,
    param_description    varchar(200),
    data_type            varchar(20)     default 'VARCHAR2',
    editable_flag        char(1)         default 'Y',
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    modified_by          varchar(30),
    modified_date        timestamp(0),
    constraint pk_system_params primary key (param_id),
    constraint uk_param_code unique (param_group, param_code)
);

create table notification_queue (
    notification_id      bigint          not null,
    recipient_emp_id     bigint,
    recipient_email      varchar(100),
    notification_type    varchar(30)     not null,
    subject              varchar(200)    not null,
    body                 text            not null,
    status               varchar(20)     default 'PENDING',
    priority             smallint        default 5,
    sent_date            timestamp(0),
    error_message        varchar(4000),
    retry_count          smallint        default 0,
    reference_table      varchar(60),
    reference_id         bigint,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_notif_queue primary key (notification_id),
    constraint chk_notif_status check (status in ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    constraint chk_notif_type check (notification_type in ('EMAIL', 'IN_APP', 'SMS'))
);

-- USERNAME widened to 100 (CUTOVER_PLAN.md §4.2 item 0.6): e-mails are not truncated
-- to 30 characters (SEC-07 divergence documented in contracts/p0-foundation/README.md).
create table user_sessions (
    session_id           bigint          not null,
    emp_id               bigint          not null,
    username             varchar(100)    not null,
    login_time           timestamp(0)    not null,
    logout_time          timestamp(0),
    ip_address           varchar(50),
    forms_module         varchar(100),
    session_status       varchar(20)     default 'ACTIVE',
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_user_sessions primary key (session_id),
    constraint fk_us_emp foreign key (emp_id) references employees (emp_id),
    constraint chk_us_status check (session_status in ('ACTIVE', 'CLOSED', 'EXPIRED'))
);

create table lookup_values (
    lookup_id            bigint          not null,
    lookup_type          varchar(50)     not null,
    lookup_code          varchar(50)     not null,
    lookup_value         varchar(200)    not null,
    display_order        integer         default 0,
    parent_lookup_id     bigint,
    active_flag          char(1)         default 'Y' not null,
    created_by           varchar(30)     not null,
    created_date         timestamp(0)    default current_timestamp not null,
    constraint pk_lookup_values primary key (lookup_id),
    constraint uk_lookup unique (lookup_type, lookup_code),
    constraint fk_lookup_parent foreign key (parent_lookup_id) references lookup_values (lookup_id)
);

-- --------------------------------------------------------------- indexes
create index ix_employees_dept      on employees (dept_id);
create index ix_employees_manager   on employees (manager_emp_id);
create index ix_employees_status    on employees (employment_status, active_flag);
create index ix_salary_emp_active   on salary_records (emp_id, active_flag);
create index ix_leave_req_approver  on leave_requests (approver_emp_id, status);
create index ix_leave_bal_emp_year  on leave_balances (emp_id, calendar_year);
create index ix_payroll_det_run     on payroll_details (run_id, emp_id);
create index ix_audit_table_record  on audit_log (table_name, record_id);
create index ix_user_sessions_emp   on user_sessions (emp_id, session_status);
create index ix_notif_status        on notification_queue (status, priority);
