package com.acme.hrms.leave;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code PKG_LEAVE.run_monthly_accrual / process_carryover / expire_carryover} as three set-based
 * SQL statements each, idempotent through {@code leave_accrual_log} ({@code uk_leave_accrual_idem}
 * on {@code (emp_id, leave_type_id, accrual_type, accrual_date)}): a re-run finds the log row and
 * touches nothing. Not scheduled and not exposed over HTTP in Phase 2 (the {@code
 * /api/leave/admin/**} routes are declared for P5); the batch is invoked by tests and by the P5
 * admin controller. No PL/pgSQL - plain SQL with CTEs (MODERNIZATION_BLUEPRINT.md §9 A).
 */
@Component
public class LeaveAccrualJob {

  /** Mirror of the frozen {@code BatchRunResult} schema. */
  public record BatchRunResult(String runId, int processed, int skipped, Instant startedAt) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public LeaveAccrualJob(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  private static final String INIT_BALANCES =
      "insert into leave_balances (balance_id, emp_id, leave_type_id, calendar_year,"
          + " opening_balance, accrued, used, adjustment, pending, created_by, created_date)"
          + " select nextval('seq_leave_balance'), e.emp_id, lt.leave_type_id, ?, 0, 0, 0, 0, 0,"
          + " ?, current_timestamp"
          + " from employees e cross join leave_types lt"
          + " where lt.active_flag = 'Y' and e.emp_id in (%s)"
          + " and not exists (select 1 from leave_balances b where b.emp_id = e.emp_id"
          + "   and b.leave_type_id = lt.leave_type_id and b.calendar_year = ?)";

  /**
   * Monthly accrual for every ACTIVE employee and every active MONTHLY-accruing leave type whose
   * tenure rule is met: {@code accrued += LEAST(rate, GREATEST(0, maxBalance - available))}, one
   * {@code ACCRUAL} log row per {@code (emp, type, accrualDate)}. Missing balance rows for the year
   * are created first ({@code initialize_balances}).
   */
  @Transactional
  public BatchRunResult runMonthlyAccrual(LocalDate accrualDate, String user) {
    Instant started = clock.instant();
    int year = accrualDate.getYear();
    String eligible =
        "select e.emp_id from employees e where e.employment_status = 'ACTIVE'"
            + " and e.active_flag = 'Y' and exists (select 1 from leave_types t"
            + " where t.active_flag = 'Y' and t.accrual_flag = 'Y' and t.accrual_frequency = 'MONTHLY'"
            + " and (?::date - e.hire_date) >= coalesce(t.min_tenure_days, 0))";
    jdbc.update(String.format(INIT_BALANCES, eligible), year, user, accrualDate, year);

    Integer skipped =
        jdbc.queryForObject(
            "select count(*) from leave_accrual_log where accrual_type = 'ACCRUAL' and accrual_date = ?",
            Integer.class,
            accrualDate);
    int processed =
        jdbc.update(
            "with cand as ("
                + " select b.balance_id, b.emp_id, b.leave_type_id,"
                + "  least(lt.accrual_rate, coalesce(greatest(0, lt.max_balance - b.available),"
                + "        lt.accrual_rate)) as amt"
                + " from leave_balances b"
                + " join leave_types lt on lt.leave_type_id = b.leave_type_id"
                + " join employees e on e.emp_id = b.emp_id"
                + " where b.calendar_year = ? and e.employment_status = 'ACTIVE' and e.active_flag = 'Y'"
                + " and lt.active_flag = 'Y' and lt.accrual_flag = 'Y' and lt.accrual_frequency = 'MONTHLY'"
                + " and (?::date - e.hire_date) >= coalesce(lt.min_tenure_days, 0)"
                + " and not exists (select 1 from leave_accrual_log l where l.emp_id = b.emp_id"
                + "   and l.leave_type_id = b.leave_type_id and l.accrual_type = 'ACCRUAL'"
                + "   and l.accrual_date = ?)"
                + "), upd as ("
                + " update leave_balances b set accrued = b.accrued + c.amt, modified_by = ?,"
                + "  modified_date = current_timestamp"
                + " from cand c where b.balance_id = c.balance_id and c.amt > 0"
                + " returning b.emp_id, b.leave_type_id, c.amt, b.available"
                + ")"
                + " insert into leave_accrual_log (accrual_id, emp_id, leave_type_id, accrual_date,"
                + "  accrual_amount, balance_after, accrual_type, created_by, created_date)"
                + " select nextval('seq_leave_accrual'), emp_id, leave_type_id, ?, amt, available,"
                + "  'ACCRUAL', ?, current_timestamp from upd",
            year,
            accrualDate,
            accrualDate,
            user,
            accrualDate,
            user);
    return new BatchRunResult(
        UUID.randomUUID().toString(), processed, skipped == null ? 0 : skipped, started);
  }

  /**
   * Year-end carryover: for every {@code year} row with a positive remaining balance ({@code
   * available = opening + accrued - used + adjustment - pending}, VAL-05: the legacy ignored {@code
   * pending}) create the {@code year+1} rows and set {@code carryoverFromPrev = openingBalance =
   * LEAST(remaining, carryoverMax)} plus {@code carryoverExpiryDt = Jan 1 (year+1) +
   * carryoverExpiry months}. One {@code CARRYOVER} log row per {@code (emp, type)} dated {@code Jan
   * 1 (year+1)}.
   */
  @Transactional
  public BatchRunResult processCarryover(int year, String user) {
    Instant started = clock.instant();
    int next = year + 1;
    LocalDate marker = LocalDate.of(next, 1, 1);
    String withRemaining =
        "select b.emp_id from leave_balances b where b.calendar_year = ?" + " and b.available > 0";
    jdbc.update(String.format(INIT_BALANCES, withRemaining), next, user, year, next);

    Integer skipped =
        jdbc.queryForObject(
            "select count(*) from leave_accrual_log where accrual_type = 'CARRYOVER' and accrual_date = ?",
            Integer.class,
            marker);
    int processed =
        jdbc.update(
            "with cand as ("
                + " select b.emp_id, b.leave_type_id,"
                + "  least(b.available, coalesce(lt.carryover_max, b.available)) as co,"
                + "  case when lt.carryover_expiry is not null"
                + "       then (?::date + (lt.carryover_expiry || ' months')::interval)::date end as expiry"
                + " from leave_balances b join leave_types lt on lt.leave_type_id = b.leave_type_id"
                + " where b.calendar_year = ? and b.available > 0"
                + " and not exists (select 1 from leave_accrual_log l where l.emp_id = b.emp_id"
                + "   and l.leave_type_id = b.leave_type_id and l.accrual_type = 'CARRYOVER'"
                + "   and l.accrual_date = ?)"
                + "), upd as ("
                + " update leave_balances n set carryover_from_prev = c.co, opening_balance = c.co,"
                + "  carryover_expiry_dt = c.expiry, modified_by = ?, modified_date = current_timestamp"
                + " from cand c where n.emp_id = c.emp_id and n.leave_type_id = c.leave_type_id"
                + "  and n.calendar_year = ? and c.co > 0"
                + " returning n.emp_id, n.leave_type_id, c.co, n.available"
                + ")"
                + " insert into leave_accrual_log (accrual_id, emp_id, leave_type_id, accrual_date,"
                + "  accrual_amount, balance_after, accrual_type, created_by, created_date)"
                + " select nextval('seq_leave_accrual'), emp_id, leave_type_id, ?, co, available,"
                + "  'CARRYOVER', ?, current_timestamp from upd",
            marker,
            year,
            marker,
            user,
            next,
            marker,
            user);
    return new BatchRunResult(
        UUID.randomUUID().toString(), processed, skipped == null ? 0 : skipped, started);
  }

  /**
   * BUG-04 corrected expiry: for rows with {@code carryoverExpiryDt <= asOf} and {@code
   * carryoverFromPrev > 0}, {@code adjustment -= GREATEST(0, carryoverFromPrev -
   * usedFromCarryover)} where {@code usedFromCarryover = LEAST(used, carryoverFromPrev)} (days are
   * taken from the carried-over portion first), then {@code carryoverFromPrev = 0}. One {@code
   * EXPIRY} log row per {@code (emp, type)} dated {@code carryoverExpiryDt}, so a second run is a
   * no-op (legacy re-deducted on every run).
   */
  @Transactional
  public BatchRunResult expireCarryover(LocalDate asOf, String user) {
    Instant started = clock.instant();
    Integer skipped =
        jdbc.queryForObject(
            "select count(*) from leave_accrual_log l join leave_balances b"
                + " on b.emp_id = l.emp_id and b.leave_type_id = l.leave_type_id"
                + " and b.carryover_expiry_dt = l.accrual_date"
                + " where l.accrual_type = 'EXPIRY' and b.carryover_expiry_dt <= ?",
            Integer.class,
            asOf);
    int processed =
        jdbc.update(
            "with cand as ("
                + " select b.balance_id, b.emp_id, b.leave_type_id, b.carryover_expiry_dt,"
                + "  greatest(0, b.carryover_from_prev - least(b.used, b.carryover_from_prev)) as forfeited"
                + " from leave_balances b"
                + " where b.carryover_expiry_dt <= ? and b.carryover_from_prev > 0"
                + " and not exists (select 1 from leave_accrual_log l where l.emp_id = b.emp_id"
                + "   and l.leave_type_id = b.leave_type_id and l.accrual_type = 'EXPIRY'"
                + "   and l.accrual_date = b.carryover_expiry_dt)"
                + "), upd as ("
                + " update leave_balances b set adjustment = b.adjustment - c.forfeited,"
                + "  carryover_from_prev = 0, modified_by = ?, modified_date = current_timestamp"
                + " from cand c where b.balance_id = c.balance_id"
                + " returning b.emp_id, b.leave_type_id, c.carryover_expiry_dt, c.forfeited, b.available"
                + ")"
                + " insert into leave_accrual_log (accrual_id, emp_id, leave_type_id, accrual_date,"
                + "  accrual_amount, balance_after, accrual_type, created_by, created_date)"
                + " select nextval('seq_leave_accrual'), emp_id, leave_type_id, carryover_expiry_dt,"
                + "  -forfeited, available, 'EXPIRY', ?, current_timestamp from upd",
            asOf,
            user,
            user);
    return new BatchRunResult(
        UUID.randomUUID().toString(), processed, skipped == null ? 0 : skipped, started);
  }
}
