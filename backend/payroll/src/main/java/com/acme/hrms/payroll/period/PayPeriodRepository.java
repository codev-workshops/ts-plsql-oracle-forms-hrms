package com.acme.hrms.payroll.period;

import com.acme.hrms.payroll.PayrollDtos.Page;
import com.acme.hrms.payroll.PayrollDtos.PayPeriod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Owner of PAY_PERIODS. */
@Repository
public class PayPeriodRepository {

  private static final Map<String, String> SORT_COLUMNS =
      Map.of(
          "periodStartDate", "p.period_start_date",
          "periodEndDate", "p.period_end_date",
          "payDate", "p.pay_date",
          "periodName", "p.period_name");

  private static final String SELECT =
      "select p.period_id, p.period_name, p.pay_frequency, p.period_start_date,"
          + " p.period_end_date, p.pay_date, p.status, p.closed_by, p.closed_date,"
          + " (select count(*) from payroll_runs r where r.period_id = p.period_id) as run_count,"
          + " lr.run_id as latest_run_id, lr.status as latest_run_status"
          + " from pay_periods p left join lateral (select r.run_id, r.status from payroll_runs r"
          + " where r.period_id = p.period_id order by r.run_id desc limit 1) lr on true";

  private final JdbcTemplate jdbc;

  public PayPeriodRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<PayPeriod> findById(long periodId) {
    return jdbc
        .query(SELECT + " where p.period_id = ?", PayPeriodRepository::map, periodId)
        .stream()
        .findFirst();
  }

  public Page<PayPeriod> list(
      @Nullable String status, String sortField, boolean asc, int page, int size) {
    StringBuilder where = new StringBuilder(" where 1 = 1");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      where.append(" and p.status = ?");
      args.add(status);
    }
    Long total =
        jdbc.queryForObject(
            "select count(*) from pay_periods p" + where, Long.class, args.toArray());
    String order =
        " order by "
            + SORT_COLUMNS.getOrDefault(sortField, "p.period_start_date")
            + (asc ? " asc" : " desc")
            + ", p.period_id "
            + (asc ? "asc" : "desc");
    args.add(size);
    args.add((long) page * size);
    List<PayPeriod> rows =
        jdbc.query(
            SELECT + where + order + " limit ? offset ?", PayPeriodRepository::map, args.toArray());
    return Page.of(rows, page, size, total == null ? 0 : total);
  }

  /** Legacy period row fields the engine needs. */
  public record PeriodCore(
      long periodId,
      String periodName,
      String payFrequency,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      LocalDate payDate,
      String status) {
    public int taxYear() {
      return periodEndDate.getYear();
    }
  }

  public Optional<PeriodCore> core(long periodId) {
    return jdbc
        .query(
            "select period_id, period_name, pay_frequency, period_start_date, period_end_date,"
                + " pay_date, status from pay_periods where period_id = ?",
            (rs, i) ->
                new PeriodCore(
                    rs.getLong("period_id"),
                    rs.getString("period_name"),
                    rs.getString("pay_frequency"),
                    rs.getObject("period_start_date", LocalDate.class),
                    rs.getObject("period_end_date", LocalDate.class),
                    rs.getObject("pay_date", LocalDate.class),
                    rs.getString("status")),
            periodId)
        .stream()
        .findFirst();
  }

  public Optional<String> statusForUpdate(long periodId) {
    return jdbc
        .query(
            "select status from pay_periods where period_id = ? for update",
            (rs, i) -> rs.getString(1),
            periodId)
        .stream()
        .findFirst();
  }

  public void setStatus(long periodId, String status, String user) {
    jdbc.update(
        "update pay_periods set status = ?, modified_by = ?, modified_date = current_timestamp"
            + " where period_id = ?",
        status,
        user,
        periodId);
  }

  public void close(long periodId, String user) {
    jdbc.update(
        "update pay_periods set status = 'CLOSED', closed_by = ?, closed_date = current_timestamp,"
            + " modified_by = ?, modified_date = current_timestamp where period_id = ?",
        user,
        user,
        periodId);
  }

  private static PayPeriod map(ResultSet rs, int i) throws SQLException {
    long latestRun = rs.getLong("latest_run_id");
    boolean hasLatest = !rs.wasNull();
    return new PayPeriod(
        rs.getLong("period_id"),
        rs.getString("period_name"),
        rs.getString("pay_frequency"),
        rs.getObject("period_start_date", LocalDate.class),
        rs.getObject("period_end_date", LocalDate.class),
        rs.getObject("pay_date", LocalDate.class),
        rs.getString("status"),
        rs.getString("closed_by"),
        rs.getObject("closed_date", LocalDateTime.class),
        rs.getInt("run_count"),
        hasLatest ? latestRun : null,
        hasLatest ? rs.getString("latest_run_status") : null);
  }
}
