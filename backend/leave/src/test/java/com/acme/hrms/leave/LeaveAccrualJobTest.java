package com.acme.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.leave.LeaveAccrualJob.BatchRunResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 1 for {@code LeaveAccrualJob} on the seed year 2024: set-based accrual (max-balance cap,
 * tenure rule, inactive employees skipped, balance rows initialised), carryover into 2025 (LEAST of
 * remaining and carryoverMax, expiry month arithmetic) and the BUG-04 corrected expiry – every run
 * idempotent through {@code leave_accrual_log} / {@code uk_leave_accrual_idem}.
 */
class LeaveAccrualJobTest {

  private static JdbcTemplate jdbc;
  private static LeaveAccrualJob job;

  /** Each run mutates the seed year, so every test starts from a fresh schema + seed. */
  @BeforeEach
  void schema() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
    job = new LeaveAccrualJob(jdbc, Clock.systemUTC());
  }

  private static Map<String, Object> bal(long empId, int typeId, int year) {
    return jdbc.queryForMap(
        "select opening_balance, accrued, used, adjustment, pending, available,"
            + " carryover_from_prev, carryover_expiry_dt from leave_balances"
            + " where emp_id = ? and leave_type_id = ? and calendar_year = ?",
        empId,
        typeId,
        year);
  }

  private static int logRows(String type) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from leave_accrual_log where accrual_type = ?", Integer.class, type);
    return n == null ? 0 : n;
  }

  private static int activeEmployees() {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from employees where employment_status = 'ACTIVE' and active_flag = 'Y'",
            Integer.class);
    return n == null ? 0 : n;
  }

  @Test
  void monthlyAccrualIsSetBasedCappedAndIdempotent() {
    LocalDate accrualDate = LocalDate.of(2024, 7, 31);
    int active = activeEmployees();
    // emp 2 PTO: available 9.5 -> +1.25 ; make emp 12 PTO already at max (20) -> no accrual
    jdbc.update("update leave_balances set accrued = 22 where balance_id = 9004"); // 0+22-2+1=21
    // emp 21 PTO 11.5 available, cap 20 -> full rate; emp 33 PTO adj -1 -> 6.5

    BatchRunResult first = job.runMonthlyAccrual(accrualDate, "batch");
    // every active employee now has a row per active leave type
    Integer rows =
        jdbc.queryForObject(
            "select count(*) from leave_balances b join employees e on e.emp_id = b.emp_id"
                + " where b.calendar_year = 2024 and e.employment_status = 'ACTIVE'",
            Integer.class);
    assertThat(rows).isEqualTo(active * 6);
    // PTO + SICK accrue monthly for every active employee except the capped 9004 row
    assertThat(first.processed()).isEqualTo(active * 2 - 1);
    assertThat(first.skipped()).isZero();
    assertThat(logRows("ACCRUAL")).isEqualTo(active * 2 - 1);

    assertThat((BigDecimal) bal(2, 1, 2024).get("accrued")).isEqualByComparingTo("8.75");
    assertThat((BigDecimal) bal(2, 1, 2024).get("available")).isEqualByComparingTo("10.75");
    assertThat((BigDecimal) bal(12, 1, 2024).get("accrued")).isEqualByComparingTo("22");
    assertThat((BigDecimal) bal(2, 2, 2024).get("accrued")).isEqualByComparingTo("5.83");
    // fresh row: 0 + rate
    assertThat((BigDecimal) bal(1, 1, 2024).get("accrued")).isEqualByComparingTo("1.25");
    assertThat(
            jdbc.queryForObject(
                "select balance_after from leave_accrual_log where emp_id = 2 and leave_type_id = 1"
                    + " and accrual_type = 'ACCRUAL'",
                BigDecimal.class))
        .isEqualByComparingTo("10.75");

    // partial cap: emp 2 PTO available 10.75; set max_balance 11 -> only 0.25 next month
    jdbc.update("update leave_types set max_balance = 11 where leave_type_id = 1");
    BatchRunResult second = job.runMonthlyAccrual(accrualDate, "batch");
    assertThat(second.processed()).isZero();
    assertThat(second.skipped()).isEqualTo(active * 2 - 1);
    assertThat((BigDecimal) bal(2, 1, 2024).get("accrued")).isEqualByComparingTo("8.75");

    BatchRunResult august = job.runMonthlyAccrual(LocalDate.of(2024, 8, 31), "batch");
    assertThat(august.processed()).isPositive();
    assertThat((BigDecimal) bal(2, 1, 2024).get("accrued")).isEqualByComparingTo("9.00");
  }

  @Test
  void carryoverCreatesNextYearRowsIdempotently() {
    // emp 11 PTO 2024: 2+7.5-4 = 5.5 remaining, carryover_max 5, expiry 3 months
    // emp 12 SICK 2024: 0+5-1 = 4 remaining, carryover_max 10, no expiry
    BatchRunResult first = job.processCarryover(2024, "batch");
    assertThat(first.skipped()).isZero();
    assertThat(first.processed()).isEqualTo(logRows("CARRYOVER"));

    Map<String, Object> pto = bal(11, 1, 2025);
    assertThat((BigDecimal) pto.get("carryover_from_prev")).isEqualByComparingTo("5");
    assertThat((BigDecimal) pto.get("opening_balance")).isEqualByComparingTo("5");
    assertThat((BigDecimal) pto.get("available")).isEqualByComparingTo("5");
    assertThat(pto.get("carryover_expiry_dt").toString()).isEqualTo("2025-04-01");

    Map<String, Object> sick = bal(12, 2, 2025);
    assertThat((BigDecimal) sick.get("carryover_from_prev")).isEqualByComparingTo("4");
    assertThat(sick.get("carryover_expiry_dt")).isNull();

    // emp 33 PTO 2024: 0+7.5-0-1 = 6.5 -> 5 ; rows for the other types initialised at zero
    assertThat((BigDecimal) bal(33, 1, 2025).get("carryover_from_prev")).isEqualByComparingTo("5");
    assertThat((BigDecimal) bal(33, 2, 2025).get("available")).isEqualByComparingTo("0");

    BatchRunResult second = job.processCarryover(2024, "batch");
    assertThat(second.processed()).isZero();
    assertThat(second.skipped()).isEqualTo(first.processed());
    assertThat((BigDecimal) bal(11, 1, 2025).get("carryover_from_prev")).isEqualByComparingTo("5");
    assertThat(logRows("CARRYOVER")).isEqualTo(first.processed());
  }

  @Test
  void expiryDeductsOnlyUnusedCarryoverOnce() {
    // seed 9001 emp 2 PTO 2024: carryover 5, used 3 -> forfeit 2 (legacy forfeited 5)
    // seed 9009 emp 31 PTO 2024: carryover 5, used 6 -> forfeit 0
    // seed 9006 emp 21 PTO 2024: carryover 4, used 0 -> forfeit 4
    jdbc.update(
        "update leave_balances set carryover_expiry_dt = date '2024-04-01'"
            + " where balance_id in (9001, 9009, 9006)");
    jdbc.update(
        "update leave_balances set carryover_expiry_dt = date '2024-12-31' where balance_id = 9003");

    BatchRunResult first = job.expireCarryover(LocalDate.of(2024, 4, 1), "batch");
    assertThat(first.processed()).isEqualTo(3);
    assertThat(first.skipped()).isZero();
    Map<String, Object> b2 = bal(2, 1, 2024);
    assertThat((BigDecimal) b2.get("adjustment")).isEqualByComparingTo("-2");
    assertThat((BigDecimal) b2.get("carryover_from_prev")).isEqualByComparingTo("0");
    assertThat((BigDecimal) b2.get("available")).isEqualByComparingTo("7.5");
    assertThat((BigDecimal) bal(31, 1, 2024).get("adjustment")).isEqualByComparingTo("0");
    assertThat((BigDecimal) bal(21, 1, 2024).get("adjustment")).isEqualByComparingTo("-4");
    // not yet due
    assertThat((BigDecimal) bal(11, 1, 2024).get("carryover_from_prev")).isEqualByComparingTo("2");
    assertThat(
            jdbc.queryForObject(
                "select accrual_amount from leave_accrual_log where emp_id = 2 and leave_type_id = 1"
                    + " and accrual_type = 'EXPIRY'",
                BigDecimal.class))
        .isEqualByComparingTo("-2");

    BatchRunResult second = job.expireCarryover(LocalDate.of(2024, 4, 1), "batch");
    assertThat(second.processed()).isZero();
    assertThat(second.skipped()).isEqualTo(3);
    assertThat((BigDecimal) bal(2, 1, 2024).get("adjustment")).isEqualByComparingTo("-2");
    assertThat(logRows("EXPIRY")).isEqualTo(3);

    BatchRunResult later = job.expireCarryover(LocalDate.of(2024, 12, 31), "batch");
    assertThat(later.processed()).isEqualTo(1);
    assertThat((BigDecimal) bal(11, 1, 2024).get("adjustment"))
        .isEqualByComparingTo("0"); // used 4 >= 2
    assertThat((BigDecimal) bal(11, 1, 2024).get("carryover_from_prev")).isEqualByComparingTo("0");
  }
}
