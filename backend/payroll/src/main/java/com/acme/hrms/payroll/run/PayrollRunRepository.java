package com.acme.hrms.payroll.run;

import com.acme.hrms.payroll.PayrollDtos;
import com.acme.hrms.payroll.PayrollDtos.PayrollRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Owner of PAYROLL_RUNS. */
@Repository
public class PayrollRunRepository {

  private static final String SELECT =
      "select run_id, period_id, run_type, run_date, status, total_gross, total_deductions,"
          + " total_net, total_employer_cost, employee_count, error_count, engine, submitted_by,"
          + " submitted_date, approved_by, approved_date, created_by, created_date,"
          + " job_execution_id, failure_message, modified_date from payroll_runs";

  private final JdbcTemplate jdbc;

  public PayrollRunRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Mutable state the service and the batch job need. */
  public record RunCore(
      long runId,
      long periodId,
      String status,
      @Nullable Long jobExecutionId,
      @Nullable String failureMessage,
      int employeeCount,
      int errorCount,
      @Nullable LocalDateTime submittedDate,
      @Nullable LocalDateTime modifiedDate) {}

  public Optional<PayrollRun> findById(long runId) {
    return jdbc.query(SELECT + " where run_id = ?", PayrollRunRepository::map, runId).stream()
        .findFirst();
  }

  public Optional<RunCore> core(long runId) {
    return jdbc.query(CORE + " where run_id = ?", PayrollRunRepository::mapCore, runId).stream()
        .findFirst();
  }

  public Optional<RunCore> coreForUpdate(long runId) {
    return jdbc
        .query(CORE + " where run_id = ? for update", PayrollRunRepository::mapCore, runId)
        .stream()
        .findFirst();
  }

  private static final String CORE =
      "select run_id, period_id, status, job_execution_id, failure_message, employee_count,"
          + " error_count, submitted_date, modified_date from payroll_runs";

  public List<PayrollRun> listByPeriod(long periodId, @Nullable String status) {
    List<Object> args = new ArrayList<>();
    args.add(periodId);
    String sql = SELECT + " where period_id = ?";
    if (status != null) {
      sql += " and status = ?";
      args.add(status);
    }
    return jdbc.query(sql + " order by run_id desc", PayrollRunRepository::map, args.toArray());
  }

  public long create(long periodId, String runType, String user) {
    Long id = jdbc.queryForObject("select nextval('seq_payroll_run')", Long.class);
    jdbc.update(
        "insert into payroll_runs (run_id, period_id, run_type, run_date, status, error_count,"
            + " engine, submitted_by, submitted_date, created_by, created_date)"
            + " values (?, ?, ?, current_timestamp, 'PENDING', 0, 'JAVA', ?, current_timestamp,"
            + " ?, current_timestamp)",
        id,
        periodId,
        runType,
        user,
        user);
    return id == null ? 0 : id;
  }

  public void markCalculating(long runId, String user) {
    jdbc.update(
        "update payroll_runs set status = 'CALCULATING', failure_message = null,"
            + " submitted_by = ?, submitted_date = current_timestamp, modified_by = ?,"
            + " modified_date = current_timestamp where run_id = ?",
        user,
        user,
        runId);
  }

  public void attachJob(long runId, long jobExecutionId) {
    jdbc.update(
        "update payroll_runs set job_execution_id = ? where run_id = ?", jobExecutionId, runId);
  }

  /**
   * Summary written once, after the last chunk: totals over non-ERROR rows (earnings positive,
   * everything else negative), employee count = distinct employees with any row.
   */
  public void finalizeCalculated(long runId, String user) {
    jdbc.update(
        "update payroll_runs set status = case when exists (select 1 from payroll_details"
            + "   where run_id = ? and status = 'CALCULATED') then 'CALCULATED' else 'ERROR' end,"
            + " failure_message = null,"
            + " employee_count = (select count(distinct emp_id) from payroll_details"
            + "   where run_id = ?),"
            + " error_count = (select count(*) from payroll_details where run_id = ?"
            + "   and status = 'ERROR'),"
            + " total_gross = (select coalesce(sum(amount), 0) from payroll_details"
            + "   where run_id = ? and element_type = 'EARNING' and status <> 'ERROR'),"
            + " total_deductions = (select coalesce(sum(abs(amount)), 0) from payroll_details"
            + "   where run_id = ? and element_type in ('TAX', 'DEDUCTION', 'BENEFIT')"
            + "   and status <> 'ERROR'),"
            + " total_net = (select coalesce(sum(amount), 0) from payroll_details"
            + "   where run_id = ? and status <> 'ERROR'),"
            + " total_employer_cost = null,"
            + " modified_by = ?, modified_date = current_timestamp where run_id = ?",
        runId,
        runId,
        runId,
        runId,
        runId,
        runId,
        user,
        runId);
  }

  public void markFailed(long runId, String message, String user) {
    jdbc.update(
        "update payroll_runs set status = 'ERROR', failure_message = ?, modified_by = ?,"
            + " modified_date = current_timestamp where run_id = ?",
        message,
        user,
        runId);
  }

  public void approve(long runId, String user) {
    jdbc.update(
        "update payroll_runs set status = 'APPROVED', approved_by = ?,"
            + " approved_date = current_timestamp, modified_by = ?,"
            + " modified_date = current_timestamp where run_id = ?",
        user,
        user,
        runId);
  }

  public void reverse(long runId, String user) {
    jdbc.update(
        "update payroll_runs set status = 'REVERSED', modified_by = ?,"
            + " modified_date = current_timestamp where run_id = ?",
        user,
        runId);
  }

  private static RunCore mapCore(ResultSet rs, int i) throws SQLException {
    long job = rs.getLong("job_execution_id");
    boolean hasJob = !rs.wasNull();
    return new RunCore(
        rs.getLong("run_id"),
        rs.getLong("period_id"),
        rs.getString("status"),
        hasJob ? job : null,
        rs.getString("failure_message"),
        rs.getInt("employee_count"),
        rs.getInt("error_count"),
        rs.getObject("submitted_date", LocalDateTime.class),
        rs.getObject("modified_date", LocalDateTime.class));
  }

  private static PayrollRun map(ResultSet rs, int i) throws SQLException {
    return new PayrollRun(
        rs.getLong("run_id"),
        rs.getLong("period_id"),
        rs.getString("run_type"),
        rs.getObject("run_date", LocalDateTime.class),
        rs.getString("status"),
        PayrollDtos.money(rs.getBigDecimal("total_gross")),
        PayrollDtos.money(rs.getBigDecimal("total_deductions")),
        PayrollDtos.money(rs.getBigDecimal("total_net")),
        PayrollDtos.nullableMoney(rs.getBigDecimal("total_employer_cost")),
        rs.getInt("employee_count"),
        rs.getInt("error_count"),
        rs.getString("engine"),
        rs.getString("submitted_by"),
        rs.getObject("submitted_date", LocalDateTime.class),
        rs.getString("approved_by"),
        rs.getObject("approved_date", LocalDateTime.class),
        rs.getString("created_by"),
        rs.getObject("created_date", LocalDateTime.class));
  }
}
