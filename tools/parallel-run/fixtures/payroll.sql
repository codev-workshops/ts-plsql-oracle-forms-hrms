-- Level-2 fixture for the `payroll.*` scenarios (PayrollScenarios.java). Apply once to the
-- PostgreSQL behind the target stack (and, when Oracle is attached, the equivalent row to the
-- legacy schema) AFTER tools/fixtures/pg/*.sql:
--
--   psql "$PG_URL" -f tools/parallel-run/fixtures/payroll.sql
--
-- Why: `payroll.calculate.no-active-salary` needs an OPEN period on which no seed employee has a
-- salary in force (PKG_PAYROLL.get_salary_as_of / SalaryAsOfReader.effectiveOn resolve
-- the salary effective on PERIOD_END_DATE; the earliest seed salary is effective 2010-03-15), so
-- every ACTIVE employee gets exactly one ERROR sentinel row -20104 and the seed period 202406 stays
-- untouched for the to-the-cent scenarios. Idempotent.
insert into pay_periods (period_id, period_name, pay_frequency, period_start_date, period_end_date,
                         pay_date, status, created_by, created_date)
values (200001, 'JAN-2000', 'MONTHLY', DATE '2000-01-01', DATE '2000-01-31', DATE '2000-01-31',
        'OPEN', 'PARALLEL-RUN', current_timestamp)
on conflict (period_id) do update set status = 'OPEN', closed_by = null, closed_date = null;
