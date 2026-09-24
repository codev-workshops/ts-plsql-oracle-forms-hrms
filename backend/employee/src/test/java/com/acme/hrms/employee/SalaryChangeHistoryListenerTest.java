package com.acme.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.salary.SalaryChangeEvent;
import com.acme.hrms.salary.SalaryChangeEvent.Kind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Level 1 – the employee-module writer behind {@code x-history: [SALARY_CHANGE]} (PostgreSQL). */
class SalaryChangeHistoryListenerTest {

  private static final long EMP = 1;
  private static final LocalDate DATE = LocalDate.of(2025, 1, 1);

  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static SalaryChangeHistoryListener listener;

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
    DataSource dataSource = HrmsPostgres.dataSource();
    jdbc = new JdbcTemplate(dataSource);
    HrmsPostgres.loadFixtures(jdbc);
    tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    listener = new SalaryChangeHistoryListener(new EmployeeHistoryRepository(jdbc));
  }

  @BeforeEach
  void clearHistory() {
    jdbc.update("delete from employee_history where emp_id = ?", EMP);
  }

  @Test
  void explicitChangeWritesExactlyOneSalaryChangeRowFromEventFields() {
    listener.onSalaryChanged(
        new SalaryChangeEvent(
            EMP,
            DATE,
            new BigDecimal("100000.00"),
            new BigDecimal("110000.00"),
            "MERIT",
            "42",
            Kind.CHANGE));

    List<Map<String, Object>> rows = history();
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(row.get("change_type")).isEqualTo("SALARY_CHANGE");
    assertThat(row.get("effective_date")).isEqualTo(java.sql.Date.valueOf(DATE));
    assertThat((BigDecimal) row.get("old_salary")).isEqualByComparingTo("100000.00");
    assertThat((BigDecimal) row.get("new_salary")).isEqualByComparingTo("110000.00");
    assertThat(row.get("reason_code")).isEqualTo("MERIT");
    assertThat(row.get("created_by")).isEqualTo("42");
    assertThat(row)
        .containsEntry("old_dept_id", null)
        .containsEntry("new_dept_id", null)
        .containsEntry("old_job_id", null)
        .containsEntry("new_job_id", null)
        .containsEntry("old_manager_id", null)
        .containsEntry("new_manager_id", null)
        .containsEntry("old_location", null)
        .containsEntry("new_location", null)
        .containsEntry("comments", null);
  }

  @Test
  void changeWithoutPriorActiveSalaryKeepsNullOldSalary() {
    listener.onSalaryChanged(
        new SalaryChangeEvent(
            EMP, DATE, null, new BigDecimal("100000.00"), "INITIAL", "42", Kind.CHANGE));

    List<Map<String, Object>> rows = history();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("old_salary")).isNull();
    assertThat(rows.get(0).get("reason_code")).isEqualTo("INITIAL");
  }

  @Test
  void initialSalaryOfANewHireIsNotASalaryChange() {
    listener.onSalaryChanged(
        new SalaryChangeEvent(
            EMP, DATE, null, new BigDecimal("85000.00"), "INITIAL", "42", Kind.INITIAL));

    assertThat(history()).isEmpty();
  }

  /** V10 widens reason_code to the contract's changeReason maxLength (50); stored verbatim. */
  @Test
  void fiftyCharacterReasonIsStoredVerbatim() {
    assertThat(
            jdbc.queryForObject(
                "select character_maximum_length from information_schema.columns"
                    + " where table_name = 'employee_history' and column_name = 'reason_code'",
                Integer.class))
        .isEqualTo(50);
    String reason = "MARKET-ADJUSTMENT-Q3-2025-RETENTION-BAND-REVIEW-XY";
    assertThat(reason).hasSize(50);
    listener.onSalaryChanged(
        new SalaryChangeEvent(EMP, DATE, null, new BigDecimal("1.00"), reason, "42", Kind.CHANGE));

    assertThat(history().get(0).get("reason_code")).isEqualTo(reason);
  }

  @Test
  void historyRowRollsBackWithTheEnclosingTransaction() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      listener.onSalaryChanged(
                          new SalaryChangeEvent(
                              EMP, DATE, null, new BigDecimal("1.00"), "MERIT", "42", Kind.CHANGE));
                      assertThat(history()).hasSize(1);
                      throw new IllegalStateException("salary insert failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(history()).isEmpty();
  }

  private static List<Map<String, Object>> history() {
    return jdbc.queryForList(
        "select * from employee_history where emp_id = ? order by hist_id", EMP);
  }
}
