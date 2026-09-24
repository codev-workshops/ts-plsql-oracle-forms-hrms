package com.acme.hrms.payroll.run;

import com.acme.hrms.payroll.PayrollDtos;
import com.acme.hrms.payroll.PayrollDtos.ApprovalWarning;
import com.acme.hrms.payroll.PayrollDtos.Page;
import com.acme.hrms.payroll.PayrollDtos.PayrollDetail;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Owner of PAYROLL_DETAILS (engine rows, sentinel ERROR rows, YTD aggregates). */
@Repository
public class PayrollDetailRepository {

  private static final String SELECT =
      "select d.detail_id, d.run_id, d.emp_id, e.emp_number, d.element_id, pe.element_code,"
          + " d.element_type, d.hours_worked, d.rate, d.amount, d.ytd_amount, d.status,"
          + " d.error_code, d.error_message from payroll_details d"
          + " join employees e on e.emp_id = d.emp_id"
          + " join pay_elements pe on pe.element_id = d.element_id";

  private final JdbcTemplate jdbc;

  public PayrollDetailRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Row to write; signed amount, {@code ytdAmount} only for the gross row. */
  public record NewDetail(
      long runId,
      long empId,
      long elementId,
      String elementType,
      BigDecimal amount,
      @Nullable BigDecimal ytdAmount,
      String status,
      @Nullable String errorCode,
      @Nullable String errorMessage,
      String createdBy) {}

  public void insertAll(List<NewDetail> rows) {
    if (rows.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        "insert into payroll_details (detail_id, run_id, emp_id, element_id, element_type, amount,"
            + " ytd_amount, status, error_code, error_message, created_by, created_date)"
            + " values (nextval('seq_payroll_detail'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " current_timestamp)",
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setLong(1, r.runId());
          ps.setLong(2, r.empId());
          ps.setLong(3, r.elementId());
          ps.setString(4, r.elementType());
          ps.setBigDecimal(5, r.amount());
          if (r.ytdAmount() == null) {
            ps.setNull(6, Types.NUMERIC);
          } else {
            ps.setBigDecimal(6, r.ytdAmount());
          }
          ps.setString(7, r.status());
          ps.setString(8, r.errorCode());
          ps.setString(9, r.errorMessage());
          ps.setString(10, r.createdBy());
        });
  }

  /** Shadow copy (CUTOVER_PLAN §8.2) of the same rows, tagged with the producing engine. */
  public void insertShadow(List<NewDetail> rows, String engine) {
    if (rows.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        "insert into payroll_details_shadow (run_id, emp_id, element_id, element_type, amount,"
            + " status, error_code, error_message, engine) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setLong(1, r.runId());
          ps.setLong(2, r.empId());
          ps.setLong(3, r.elementId());
          ps.setString(4, r.elementType());
          ps.setBigDecimal(5, r.amount());
          ps.setString(6, r.status());
          ps.setString(7, r.errorCode());
          ps.setString(8, r.errorMessage());
          ps.setString(9, engine);
        });
  }

  public int deleteShadowByRunAndEmployee(long runId, long empId, String engine) {
    return jdbc.update(
        "delete from payroll_details_shadow where run_id = ? and emp_id = ? and engine = ?",
        runId,
        empId,
        engine);
  }

  public int deleteByRun(long runId) {
    return jdbc.update("delete from payroll_details where run_id = ?", runId);
  }

  public int deleteByRunAndEmployee(long runId, long empId) {
    return jdbc.update("delete from payroll_details where run_id = ? and emp_id = ?", runId, empId);
  }

  public void reverseByRun(long runId) {
    jdbc.update(
        "update payroll_details set status = 'REVERSED' where run_id = ? and status <> 'ERROR'",
        runId);
  }

  public Page<PayrollDetail> list(
      long runId, @Nullable Long empId, @Nullable String status, int page, int size) {
    StringBuilder where = new StringBuilder(" where d.run_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(runId);
    if (empId != null) {
      where.append(" and d.emp_id = ?");
      args.add(empId);
    }
    if (status != null) {
      where.append(" and d.status = ?");
      args.add(status);
    }
    Long total =
        jdbc.queryForObject(
            "select count(*) from payroll_details d" + where, Long.class, args.toArray());
    args.add(size);
    args.add((long) page * size);
    List<PayrollDetail> rows =
        jdbc.query(
            SELECT + where + " order by d.emp_id, d.element_id, d.detail_id limit ? offset ?",
            PayrollDetailRepository::map,
            args.toArray());
    return Page.of(rows, page, size, total == null ? 0 : total);
  }

  public List<PayrollDetail> forEmployee(long runId, long empId) {
    return jdbc.query(
        SELECT + " where d.run_id = ? and d.emp_id = ? order by d.element_id, d.detail_id",
        PayrollDetailRepository::map,
        runId,
        empId);
  }

  public List<ApprovalWarning> errorRows(long runId) {
    return jdbc.query(
        "select d.emp_id, e.emp_number, d.error_code, d.error_message from payroll_details d"
            + " join employees e on e.emp_id = d.emp_id where d.run_id = ? and d.status = 'ERROR'"
            + " order by d.emp_id",
        (rs, i) ->
            new ApprovalWarning(
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                Optional.ofNullable(rs.getString("error_code")).orElse("INTERNAL_ERROR"),
                Optional.ofNullable(rs.getString("error_message")).orElse("")),
        runId);
  }

  public int countProcessedEmployees(long runId) {
    Integer n =
        jdbc.queryForObject(
            "select count(distinct emp_id) from payroll_details where run_id = ?",
            Integer.class,
            runId);
    return n == null ? 0 : n;
  }

  public int eligibleEmployeeTotal() {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from employees where employment_status = 'ACTIVE'"
                + " and active_flag = 'Y'",
            Integer.class);
    return n == null ? 0 : n;
  }

  public int countErrorRows(long runId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from payroll_details where run_id = ? and status = 'ERROR'",
            Integer.class,
            runId);
    return n == null ? 0 : n;
  }

  /**
   * TAX-YTD-01: earnings of every run (any status) whose period starts in the tax year, including
   * rows of the current run already written.
   */
  public BigDecimal taxYtdGross(long empId, int taxYear) {
    BigDecimal v =
        jdbc.queryForObject(
            "select coalesce(sum(d.amount), 0) from payroll_details d"
                + " join payroll_runs r on r.run_id = d.run_id"
                + " join pay_periods p on p.period_id = r.period_id"
                + " where d.emp_id = ? and d.element_type = 'EARNING' and d.status = 'CALCULATED'"
                + " and extract(year from p.period_start_date) = ?",
            BigDecimal.class,
            empId,
            taxYear);
    return v == null ? BigDecimal.ZERO : v;
  }

  /** Reporting YTD (BUG-06): APPROVED/PAID runs of the tax year through the period end. */
  public record Ytd(BigDecimal gross, BigDecimal taxes, BigDecimal deductions, BigDecimal net) {}

  public Ytd reportingYtd(long empId, int taxYear, LocalDate throughPeriodEnd) {
    return jdbc.queryForObject(
        "select coalesce(sum(case when d.element_type = 'EARNING' then d.amount else 0 end), 0),"
            + " coalesce(sum(case when d.element_type = 'TAX' then abs(d.amount) else 0 end), 0),"
            + " coalesce(sum(case when d.element_type in ('DEDUCTION', 'BENEFIT') then abs(d.amount)"
            + "   else 0 end), 0),"
            + " coalesce(sum(d.amount), 0)"
            + " from payroll_details d join payroll_runs r on r.run_id = d.run_id"
            + " join pay_periods p on p.period_id = r.period_id"
            + " where d.emp_id = ? and d.status = 'CALCULATED' and r.status in ('APPROVED', 'PAID')"
            + " and extract(year from p.period_start_date) = ? and p.period_end_date <= ?",
        (rs, i) ->
            new Ytd(
                rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)),
        empId,
        taxYear,
        throughPeriodEnd);
  }

  private static PayrollDetail map(ResultSet rs, int i) throws SQLException {
    BigDecimal hours = rs.getBigDecimal("hours_worked");
    return new PayrollDetail(
        rs.getLong("detail_id"),
        rs.getLong("run_id"),
        rs.getLong("emp_id"),
        rs.getString("emp_number"),
        rs.getLong("element_id"),
        rs.getString("element_code"),
        rs.getString("element_type"),
        PayrollDtos.nullableMoney(hours),
        PayrollDtos.rate(rs.getBigDecimal("rate")),
        PayrollDtos.money(rs.getBigDecimal("amount")),
        PayrollDtos.nullableMoney(rs.getBigDecimal("ytd_amount")),
        rs.getString("status"),
        rs.getString("error_code"),
        rs.getString("error_message"));
  }
}
