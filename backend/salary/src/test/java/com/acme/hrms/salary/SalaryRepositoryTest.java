package com.acme.hrms.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.salary.SalaryRecordRepository.NewSalary;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

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
    jdbc.queryForObject(
        "select setval('seq_salary', coalesce((select max(salary_id) from salary_records), 0), true)",
        Long.class);
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

  private static long insert(LocalDate date, BigDecimal amount) {
    return records.insert(
        new NewSalary(
            1, date, null, amount, "USD", "MONTHLY", "ANNUAL", "TEST", null, false, "tester"));
  }
}
