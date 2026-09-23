package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.BatchRunResult;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.leave.LeaveAccrualJob;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Admin trigger for the leave batch jobs. A {@code leave_jobs} row is the lock: the partial unique
 * index on {@code (job_type, job_key) where status='RUNNING'} turns a concurrent start into {@code
 * -20702}. Work runs on {@code executor} after the row is committed; the HTTP call returns {@code
 * 202} immediately.
 */
@Service
public class LeaveJobTrigger {
  private static final RowMapper<BatchRunResult> MAPPER =
      (rs, i) ->
          new BatchRunResult(
              rs.getObject("job_id", UUID.class),
              rs.getString("job_type"),
              rs.getString("status"),
              rs.getInt("processed"),
              rs.getInt("skipped"),
              rs.getInt("failed"),
              rs.getObject("started_at", LocalDateTime.class),
              rs.getObject("finished_at", LocalDateTime.class),
              rs.getString("started_by"),
              rs.getString("message"));

  private final NamedParameterJdbcTemplate jdbc;
  private final LeaveAccrualJob job;
  private final Executor executor;
  private final Clock clock;

  public LeaveJobTrigger(
      NamedParameterJdbcTemplate jdbc,
      LeaveAccrualJob job,
      Executor leaveJobExecutor,
      Clock clock) {
    this.jdbc = jdbc;
    this.job = job;
    this.executor = leaveJobExecutor;
    this.clock = clock;
  }

  public BatchRunResult startAccrual(@Nullable LocalDate accrualDate, String user) {
    LocalDate date = accrualDate == null ? LocalDate.now(clock) : accrualDate;
    UUID id = insertRunning("ACCRUAL", date.toString(), user, "accrualDate");
    executor.execute(() -> run(id, () -> job.runMonthlyAccrual(date, user)));
    return get(id);
  }

  public BatchRunResult startCarryover(int year, String user) {
    UUID id = insertRunning("CARRYOVER", Integer.toString(year), user, "year");
    executor.execute(() -> run(id, () -> job.processCarryover(year, user)));
    return get(id);
  }

  public BatchRunResult get(UUID jobId) {
    List<BatchRunResult> rows =
        jdbc.query("select * from leave_jobs where job_id = :id", Map.of("id", jobId), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.JOB_NOT_FOUND);
    }
    return rows.get(0);
  }

  private UUID insertRunning(String type, String key, String user, String field) {
    UUID id = UUID.randomUUID();
    Map<String, Object> p = new HashMap<>();
    p.put("id", id);
    p.put("type", type);
    p.put("key", key);
    p.put("user", user);
    p.put("now", LocalDateTime.now(clock));
    try {
      jdbc.update(
          """
          insert into leave_jobs (job_id, job_type, job_key, status, started_at, started_by)
          values (:id, :type, :key, 'RUNNING', :now, :user)
          """,
          p);
    } catch (DuplicateKeyException e) {
      throw new HrmsException(
          ErrorCode.JOB_ALREADY_RUNNING,
          String.format(ErrorCode.JOB_ALREADY_RUNNING.defaultMessage(), type, key),
          field,
          e);
    }
    return id;
  }

  private void run(UUID id, java.util.function.Supplier<LeaveAccrualJob.BatchRunResult> work) {
    Map<String, Object> p = new HashMap<>();
    p.put("id", id);
    try {
      LeaveAccrualJob.BatchRunResult r = work.get();
      p.put("processed", r.processed());
      p.put("skipped", r.skipped());
      p.put("now", LocalDateTime.now(clock));
      jdbc.update(
          "update leave_jobs set status = 'COMPLETED', processed = :processed, skipped = :skipped,"
              + " finished_at = :now where job_id = :id",
          p);
    } catch (RuntimeException e) {
      p.put("msg", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      p.put("now", LocalDateTime.now(clock));
      jdbc.update(
          "update leave_jobs set status = 'FAILED', failed = 1, message = :msg, finished_at = :now"
              + " where job_id = :id",
          p);
    }
  }
}
