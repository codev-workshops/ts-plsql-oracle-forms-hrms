package com.acme.hrms.payroll.run;

import com.acme.hrms.payroll.period.PayPeriodRepository;
import com.acme.hrms.payroll.period.PayPeriodRepository.PeriodCore;
import com.acme.hrms.payroll.run.PayrollDetailRepository.NewDetail;
import com.acme.hrms.payroll.tax.TaxRuleRepository;
import com.acme.hrms.payroll.tax.TaxRules;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job behind POST /runs/{runId}/calculate: one chunked step (50 employees per
 * transaction) over the eligible employees in emp_id order, restartable via the job repository.
 */
@Configuration
public class PayrollBatchConfig {

  public static final String JOB_NAME = "payrollCalculation";
  public static final String PARAM_RUN_ID = "runId";
  public static final String PARAM_USER = "user";
  public static final String PARAM_ATTEMPT = "attempt";
  public static final int CHUNK_SIZE = 50;

  @Bean
  public JobLauncher payrollJobLauncher(JobRepository jobRepository) throws Exception {
    TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
    launcher.setJobRepository(jobRepository);
    launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("payroll-calc-"));
    launcher.afterPropertiesSet();
    return launcher;
  }

  @Bean
  public Job payrollCalculationJob(
      JobRepository jobRepository, Step payrollCalculationStep, PayrollRunJobListener listener) {
    return new JobBuilder(JOB_NAME, jobRepository)
        .listener(listener)
        .start(payrollCalculationStep)
        .build();
  }

  @Bean
  public Step payrollCalculationStep(
      JobRepository jobRepository,
      PlatformTransactionManager txManager,
      JdbcPagingItemReader<Long> eligibleEmployeeReader,
      ItemProcessor<Long, List<NewDetail>> employeePayProcessor,
      ItemWriter<List<NewDetail>> payrollDetailWriter) {
    return new StepBuilder("calculateEmployees", jobRepository)
        .<Long, List<NewDetail>>chunk(CHUNK_SIZE, txManager)
        .reader(eligibleEmployeeReader)
        .processor(employeePayProcessor)
        .writer(payrollDetailWriter)
        .build();
  }

  @Bean
  @StepScope
  public JdbcPagingItemReader<Long> eligibleEmployeeReader(DataSource dataSource) {
    PostgresPagingQueryProvider provider = new PostgresPagingQueryProvider();
    provider.setSelectClause("select emp_id");
    provider.setFromClause("from employees");
    provider.setWhereClause("where employment_status = 'ACTIVE' and active_flag = 'Y'");
    provider.setSortKeys(Map.of("emp_id", Order.ASCENDING));
    return new JdbcPagingItemReaderBuilder<Long>()
        .name("eligibleEmployeeReader")
        .dataSource(dataSource)
        .queryProvider(provider)
        .pageSize(CHUNK_SIZE)
        .rowMapper((rs, i) -> rs.getLong("emp_id"))
        .saveState(true)
        .build();
  }

  @Bean
  @StepScope
  public ItemProcessor<Long, List<NewDetail>> employeePayProcessor(
      @Value("#{jobParameters['" + PARAM_RUN_ID + "']}") Long runId,
      @Value("#{jobParameters['" + PARAM_USER + "']}") String user,
      PayrollRunRepository runs,
      PayPeriodRepository periods,
      TaxRuleRepository taxRules,
      EmployeePayCalculator calculator) {
    PayrollRunRepository.RunCore run =
        runs.core(runId).orElseThrow(() -> new IllegalStateException("run " + runId + " vanished"));
    PeriodCore period =
        periods
            .core(run.periodId())
            .orElseThrow(() -> new IllegalStateException("period " + run.periodId() + " vanished"));
    TaxRules rules = taxRules.load(period.taxYear());
    return empId -> calculator.calculate(runId, period, rules, empId, user);
  }

  @Bean
  public ItemWriter<List<NewDetail>> payrollDetailWriter(PayrollDetailRepository details) {
    return chunk -> {
      for (List<NewDetail> rows : chunk) {
        if (!rows.isEmpty()) {
          NewDetail first = rows.get(0);
          details.deleteByRunAndEmployee(first.runId(), first.empId());
          details.insertAll(rows);
          details.deleteShadowByRunAndEmployee(first.runId(), first.empId(), "JAVA");
          details.insertShadow(rows, "JAVA");
        }
      }
    };
  }

  /** Writes the run summary when the job ends (either way) so pollers see a terminal status. */
  @Component
  public static class PayrollRunJobListener implements JobExecutionListener {

    private final PayrollRunRepository runs;

    public PayrollRunJobListener(PayrollRunRepository runs) {
      this.runs = runs;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
      Long runId = jobExecution.getJobParameters().getLong(PARAM_RUN_ID);
      String user = jobExecution.getJobParameters().getString(PARAM_USER);
      if (runId == null) {
        return;
      }
      String actor = user == null ? "SYSTEM" : user;
      if (jobExecution.getStatus().isUnsuccessful()) {
        String message =
            jobExecution.getAllFailureExceptions().stream()
                .map(Throwable::toString)
                .findFirst()
                .orElse(jobExecution.getExitStatus().getExitDescription());
        runs.markFailed(
            runId, message.length() > 2000 ? message.substring(0, 2000) : message, actor);
      } else {
        runs.finalizeCalculated(runId, actor);
      }
    }
  }
}
