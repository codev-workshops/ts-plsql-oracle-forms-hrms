-- Level-2 fixture for the `leave.*` scenarios (LeaveScenarios.java). Apply once to the
-- PostgreSQL behind the target stack (and, when Oracle is attached, the equivalent rows to the
-- legacy schema) AFTER tools/fixtures/pg/*.sql:
--
--   psql "$PG_URL" -f tools/parallel-run/fixtures/leave.sql
--
-- Why: the seed only carries 2024 balances and 2024 holidays, but PKG_LEAVE / LeaveRequestService
-- reject start dates more than 5 days in the past (-20211) and check the balance of the *current*
-- year (QUIRK-01), so the scenarios submit requests in 2030..2034 (one calendar year per
-- balance-observing scenario) as seed user emp 2 (sarah.chen). Idempotent (upserts on UK_LEAVE_BAL /
-- primary keys).

-- 1. emp 2 PTO: 10 available in the current year (balance check) and in every scenario year
--    (pending / used mutations go to the start-date year).
insert into leave_balances (balance_id, emp_id, leave_type_id, calendar_year, opening_balance,
                            accrued, used, adjustment, pending, carryover_from_prev, created_by, created_date)
select 9900 + (y - 2029), 2, 1, y, 10, 0, 0, 0, 0, 0, 'PARALLEL-RUN', current_timestamp
from generate_series(2030, 2034) y
on conflict (emp_id, leave_type_id, calendar_year)
    do update set opening_balance = 10, accrued = 0, used = 0, adjustment = 0, pending = 0;

insert into leave_balances (balance_id, emp_id, leave_type_id, calendar_year, opening_balance,
                            accrued, used, adjustment, pending, carryover_from_prev, created_by, created_date)
values (9906, 2, 1, extract(year from current_date)::int, 10, 0, 0, 0, 0, 0, 'PARALLEL-RUN', current_timestamp)
on conflict (emp_id, leave_type_id, calendar_year)
    do update set opening_balance = 10, accrued = 0, used = 0, adjustment = 0, pending = 0;

-- 2. BUG-05: a Saturday holiday. Legacy calculate_business_days ignores it (weekend);
--    BusinessCalendar observes it on Friday 2030-03-01.
insert into holidays (holiday_id, holiday_name, holiday_date, location_code, active_flag, created_by, created_date)
values (9901, 'Parallel-run Saturday holiday', date '2030-03-02', null, 'Y', 'PARALLEL-RUN', current_timestamp)
on conflict (holiday_id) do nothing;

-- 3. -20203 (tenure): an active leave type no seed employee can satisfy.
insert into leave_types (leave_type_id, leave_type_code, leave_type_name, accrual_flag, accrual_rate,
                         accrual_frequency, max_balance, carryover_max, carryover_expiry, requires_approval,
                         min_tenure_days, active_flag, created_by, created_date)
values (99, 'TENURE99', 'Parallel-run tenure gate', 'N', null, null, null, 0, null, 'Y', 36500, 'Y',
        'PARALLEL-RUN', current_timestamp)
on conflict (leave_type_id) do nothing;

-- 4. Re-run cleanup: drop the requests the scenarios created (ids come from seq_leave_request).
delete from leave_requests where emp_id = 2 and start_date >= date '2030-01-01';
