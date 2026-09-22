package com.acme.hrms.payroll.run;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.payroll.PayrollDtos.ApprovalWarning;
import com.acme.hrms.payroll.PayrollDtos.Page;
import com.acme.hrms.payroll.PayrollDtos.PayrollDetail;
import com.acme.hrms.payroll.PayrollDtos.PayrollRun;
import com.acme.hrms.payroll.PayrollDtos.PayrollRunApproval;
import com.acme.hrms.payroll.PayrollDtos.PayrollRunStatus;
import com.acme.hrms.payroll.period.PayPeriodRepository;
import com.acme.hrms.payroll.run.PayrollRunRepository.RunCore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Run state machine (PKG_PAYROLL.create_payroll_run / approve_payroll_run + Java-only states). */
@Service
public class PayrollRunService {

  private static final Set<String> CALCULABLE = Set.of("PENDING", "CALCULATED", "ERROR");
  private static final Set<String> REVERSIBLE = Set.of("CALCULATED", "APPROVED", "PAID", "ERROR");

  private final PayrollRunRepository runs;
  private final PayrollDetailRepository details;
  private final PayPeriodRepository periods;
  private final AuditService audit;
  private final JobLauncher jobLauncher;
  private final JobExplorer jobExplorer;
  private final Job calculationJob;
  private final TransactionTemplate tx;

  public PayrollRunService(
      PayrollRunRepository runs,
      PayrollDetailRepository details,
      PayPeriodRepository periods,
      AuditService audit,
      JobLauncher payrollJobLauncher,
      JobExplorer jobExplorer,
      Job payrollCalculationJob,
      PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.details = details;
    this.periods = periods;
    this.audit = audit;
    this.jobLauncher = payrollJobLauncher;
    this.jobExplorer = jobExplorer;
    this.calculationJob = payrollCalculationJob;
    this.tx = new TransactionTemplate(transactionManager);
  }

  public List<PayrollRun> listByPeriod(long periodId, @Nullable String status) {
    requirePeriod(periodId);
    return runs.listByPeriod(periodId, status);
  }

  public PayrollRun get(long runId) {
    return runs.findById(runId).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
  }

  @Transactional
  public PayrollRun create(long periodId, String runType, CallerIdentity caller) {
    String status =
        periods
            .statusForUpdate(periodId)
            .orElseThrow(() -> new HrmsException(ErrorCode.PERIOD_NOT_FOUND));
    if ("CLOSED".equals(status)) {
      throw fmt(ErrorCode.PERIOD_CLOSED, periodId);
    }
    long runId = runs.create(periodId, runType, caller.userId());
    periods.setStatus(periodId, "PROCESSING", caller.userId());
    audit.log(
        "PAYROLL_RUNS",
        runId,
        AuditService.Action.INSERT,
        null,
        "status=PENDING",
        caller.userId(),
        null,
        null);
    return get(runId);
  }

  /**
   * Flips the run to CALCULATING in its own (committed) transaction, then launches the job: the
   * Spring Batch JobRepository refuses to create a JobExecution inside a caller transaction.
   */
  public PayrollRunStatus calculate(long runId, CallerIdentity caller) {
    RunCore run =
        Objects.requireNonNull(
            tx.execute(
                status -> {
                  RunCore core =
                      runs.coreForUpdate(runId)
                          .orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
                  if ("CALCULATING".equals(core.status())) {
                    throw new HrmsException(ErrorCode.RUN_ALREADY_CALCULATING);
                  }
                  if (!CALCULABLE.contains(core.status())) {
                    throw fmt(ErrorCode.RUN_NOT_CALCULABLE, core.status());
                  }
                  runs.markCalculating(runId, caller.userId());
                  return core;
                }));
    launch(runId, parametersFor(run, caller.userId()), caller.userId());
    return new PayrollRunStatus(
        runId, "CALCULATING", run.jobExecutionId(), 0, null, 0, null, null, null);
  }

  private JobParameters parametersFor(RunCore run, String user) {
    if (run.jobExecutionId() != null) {
      JobExecution previous = jobExplorer.getJobExecution(run.jobExecutionId());
      if (previous != null && previous.getStatus() == BatchStatus.FAILED) {
        return previous.getJobParameters();
      }
    }
    return new JobParametersBuilder()
        .addLong(PayrollBatchConfig.PARAM_RUN_ID, run.runId())
        .addString(PayrollBatchConfig.PARAM_USER, user, false)
        .addLong(PayrollBatchConfig.PARAM_ATTEMPT, System.nanoTime())
        .toJobParameters();
  }

  private void launch(long runId, JobParameters params, String user) {
    try {
      JobExecution execution = jobLauncher.run(calculationJob, params);
      runs.attachJob(runId, execution.getId());
    } catch (JobExecutionAlreadyRunningException
        | JobRestartException
        | JobInstanceAlreadyCompleteException
        | JobParametersInvalidException e) {
      runs.markFailed(runId, e.toString(), user);
    }
  }

  public PayrollRunStatus status(long runId) {
    RunCore run = runs.core(runId).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
    Integer total = null;
    LocalDateTime started = run.submittedDate();
    LocalDateTime finished = null;
    int processed;
    int errors;
    if (run.jobExecutionId() != null) {
      JobExecution exec = jobExplorer.getJobExecution(run.jobExecutionId());
      if (exec != null) {
        started = exec.getStartTime() == null ? started : exec.getStartTime();
        finished = exec.getEndTime();
      }
    }
    boolean live = "CALCULATING".equals(run.status());
    if (live) {
      processed = details.countProcessedEmployees(runId);
      errors = details.countErrorRows(runId);
    } else {
      processed = run.employeeCount();
      errors = run.errorCount();
      if (finished == null && !"PENDING".equals(run.status())) {
        finished = run.modifiedDate();
      }
    }
    if (!"PENDING".equals(run.status())) {
      total = eligibleTotal(run, processed);
    }
    return new PayrollRunStatus(
        runId,
        run.status(),
        run.jobExecutionId(),
        processed,
        total,
        errors,
        started,
        finished,
        run.failureMessage());
  }

  private Integer eligibleTotal(RunCore run, int processed) {
    if (!"CALCULATING".equals(run.status())) {
      return processed;
    }
    return details.eligibleEmployeeTotal();
  }

  @Transactional
  public PayrollRunApproval approve(long runId, CallerIdentity caller) {
    RunCore run =
        runs.coreForUpdate(runId).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
    if (!"CALCULATED".equals(run.status())) {
      throw fmt(ErrorCode.RUN_NOT_APPROVABLE, run.status());
    }
    List<ApprovalWarning> warnings = details.errorRows(runId);
    runs.approve(runId, caller.userId());
    audit.log(
        "PAYROLL_RUNS",
        runId,
        AuditService.Action.PAYROLL_APPROVE,
        "status=CALCULATED",
        "status=APPROVED",
        caller.userId(),
        null,
        null);
    return new PayrollRunApproval(get(runId), warnings);
  }

  @Transactional
  public PayrollRun reverse(long runId, String reason, CallerIdentity caller) {
    RunCore run =
        runs.coreForUpdate(runId).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
    if (!REVERSIBLE.contains(run.status())) {
      throw fmt(ErrorCode.RUN_NOT_REVERSIBLE, run.status());
    }
    details.reverseByRun(runId);
    runs.reverse(runId, caller.userId());
    periods.setStatus(run.periodId(), "OPEN", caller.userId());
    audit.log(
        "PAYROLL_RUNS",
        runId,
        AuditService.Action.PAYROLL_REVERSE,
        "status=" + run.status(),
        "status=REVERSED;reason=" + reason,
        caller.userId(),
        null,
        null);
    return get(runId);
  }

  public Page<PayrollDetail> details(
      long runId, @Nullable Long empId, @Nullable String status, int page, int size) {
    if (runs.core(runId).isEmpty()) {
      throw new HrmsException(ErrorCode.RUN_NOT_FOUND);
    }
    return details.list(runId, empId, status, page, size);
  }

  static HrmsException fmt(ErrorCode code, Object arg) {
    return new HrmsException(code, String.format(code.defaultMessage(), arg), null);
  }

  private void requirePeriod(long periodId) {
    if (periods.core(periodId).isEmpty()) {
      throw new HrmsException(ErrorCode.PERIOD_NOT_FOUND);
    }
  }
}
