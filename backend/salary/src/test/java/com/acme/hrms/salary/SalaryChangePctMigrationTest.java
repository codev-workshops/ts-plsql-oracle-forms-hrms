package com.acme.hrms.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.salary.SalaryRecordRepository.NewSalary;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V11 widens change_pct on an existing V10 database in place, keeping its rows. */
class SalaryChangePctMigrationTest {

  @AfterAll
  static void restoreLatestSchema() {
    HrmsPostgres.resetSchema();
  }

  @Test
  void v11WidensChangePctOnAnExistingV10DatabaseAndKeepsExistingValues() {
    Flyway flyway = HrmsPostgres.flyway();
    flyway.clean();
    Flyway.configure().configuration(flyway.getConfiguration()).target("10").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
    SalaryRecordRepository records = new SalaryRecordRepository(jdbc);
    jdbc.update("delete from salary_records where emp_id in (1, 2, 3, 10)");

    long legacyMax = insert(records, 1, "999.99", true);
    long legacyCut = insert(records, 2, "-12.34", false);
    long legacyNull = insert(records, 2, null, true);
    assertThatThrownBy(() -> insert(records, 3, "1415.15", false))
        .as("V10 change_pct is still the legacy numeric(5,2)")
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("numeric field overflow");

    flyway.migrate();

    assertThat(
            jdbc.queryForObject(
                "select max(version::int) from flyway_schema_history where success", Integer.class))
        .isEqualTo(11);
    assertThat(records.findById(legacyMax).orElseThrow().changePct()).isEqualTo("999.99");
    assertThat(records.findById(legacyCut).orElseThrow().changePct()).isEqualTo("-12.34");
    assertThat(records.findById(legacyNull).orElseThrow().changePct()).isNull();

    long reported = insert(records, 3, "1415.15", true);
    long ceiling = insert(records, 10, "99999999999800.00", true);
    assertThat(records.findById(reported).orElseThrow().changePct()).isEqualTo("1415.15");
    assertThat(records.findById(ceiling).orElseThrow().changePct()).isEqualTo("99999999999800.00");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from salary_records where emp_id = 2 and active_flag = 'Y'",
                Integer.class))
        .isEqualTo(1);
  }

  private static long insert(
      SalaryRecordRepository records, long empId, String changePct, boolean active) {
    long id =
        records.insert(
            new NewSalary(
                empId,
                LocalDate.of(2025, 1, 1),
                null,
                new BigDecimal("100000.00"),
                "USD",
                "MONTHLY",
                "ANNUAL",
                "TEST",
                changePct == null ? null : new BigDecimal(changePct),
                false,
                "tester"));
    if (!active) {
      new JdbcTemplate(HrmsPostgres.dataSource())
          .update(
              "update salary_records set active_flag = 'N', end_date = date '2025-01-01' where"
                  + " salary_id = ?",
              id);
    }
    return id;
  }
}
