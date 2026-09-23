package com.acme.hrms.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.salary.SalaryRecordRepository.NewSalary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SalaryRepositoryTest {

  private static JdbcTemplate jdbc;
  private static SalaryRecordRepository records;

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
    records = new SalaryRecordRepository(jdbc);
  }

  @BeforeEach
  void isolateEmployee() {
    jdbc.update("delete from salary_records where emp_id = 1");
  }

  @Test
  void flywayV5AppliedAndAddsSalaryIndexesAndColumn() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '5' and success",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name ="
                    + " 'salary_records' and column_name = 'out_of_grade_band'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = 'uk_salary_emp_active'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void partialUniqueIndexAllowsOnlyOneActiveRow() {
    insert(LocalDate.of(2025, 1, 1), BigDecimal.valueOf(100));
    assertThatThrownBy(() -> insert(LocalDate.of(2025, 2, 1), BigDecimal.valueOf(110)))
        .isInstanceOf(DuplicateKeyException.class);
    jdbc.update(
        "update salary_records set active_flag = 'N', end_date = date '2025-02-01' where emp_id = 1");
    assertThat(insert(LocalDate.of(2025, 2, 1), BigDecimal.valueOf(110))).isPositive();
  }

  @Test
  void closeActiveAndInsertPreservesSingleActiveRow() {
    insert(LocalDate.of(2025, 1, 1), BigDecimal.valueOf(100));
    assertThat(records.closeActive(1, LocalDate.of(2025, 2, 1), "tester")).isEqualTo(1);
    insert(LocalDate.of(2025, 2, 1), BigDecimal.valueOf(110));
    assertThat(records.findActive(1)).isPresent();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 1 and active_flag = 'Y'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void historyUsesEffectiveDateThenSalaryIdDescending() {
    long first = insert(LocalDate.of(2025, 3, 1), BigDecimal.valueOf(100));
    jdbc.update("update salary_records set active_flag = 'N' where salary_id = ?", first);
    long second = insert(LocalDate.of(2025, 3, 1), BigDecimal.valueOf(110));
    jdbc.update("update salary_records set active_flag = 'N' where salary_id = ?", second);
    long third = insert(LocalDate.of(2025, 4, 1), BigDecimal.valueOf(120));
    assertThat(records.history(1))
        .extracting(SalaryDtos.SalaryRecord::salaryId)
        .containsExactly(third, second, first);
  }

  @Test
  void concurrentCloseAndInsertKeepsExactlyOneActiveRowAndRollsBackLoser() throws Exception {
    long original = insert(LocalDate.of(2025, 1, 1), BigDecimal.valueOf(100));
    DataSource shared = HrmsPostgres.dataSource();
    SalaryRecordRepository racing = new SalaryRecordRepository(new JdbcTemplate(shared));
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(shared));
    CyclicBarrier bothRead = new CyclicBarrier(2);
    Callable<Long> change =
        () ->
            tx.execute(
                status -> {
                  assertThat(racing.findActive(1)).isPresent();
                  await(bothRead);
                  racing.closeActive(1, LocalDate.of(2025, 2, 1), "racer");
                  return racing.insert(
                      new NewSalary(
                          1,
                          LocalDate.of(2025, 2, 1),
                          null,
                          BigDecimal.valueOf(110),
                          "USD",
                          "MONTHLY",
                          "ANNUAL",
                          "RACE",
                          null,
                          false,
                          "racer"));
                });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Long>> outcomes = pool.invokeAll(List.of(change, change));
      int winners = 0;
      int losers = 0;
      for (Future<Long> outcome : outcomes) {
        try {
          assertThat(outcome.get()).isPositive();
          winners++;
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(DuplicateKeyException.class);
          losers++;
        }
      }
      assertThat(winners).isEqualTo(1);
      assertThat(losers).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 1 and active_flag = 'Y'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 1", Integer.class))
        .isEqualTo(2);
    assertThat(records.findById(original).orElseThrow().active()).isFalse();
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static long insert(LocalDate date, BigDecimal amount) {
    return records.insert(
        new NewSalary(
            1, date, null, amount, "USD", "MONTHLY", "ANNUAL", "TEST", null, false, "tester"));
  }
}
