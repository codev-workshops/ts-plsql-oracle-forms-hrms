package com.acme.hrms.salary;

import com.acme.hrms.salary.SalaryDtos.SalaryRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC owner of {@code salary_records}; all state changes are explicit service operations.
 */
@Repository
public class SalaryRecordRepository {

  private static final String COLUMNS =
      "salary_id, emp_id, effective_date, end_date, base_salary, currency_code,"
          + " pay_frequency, salary_basis, change_reason, change_pct, active_flag,"
          + " out_of_grade_band, created_by, created_date";

  private final JdbcTemplate jdbc;

  public SalaryRecordRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<SalaryRecord> findActive(long empId) {
    return jdbc
        .query(
            "select " + COLUMNS + " from salary_records where emp_id = ? and active_flag = 'Y'",
            SalaryRecordRepository::map,
            empId)
        .stream()
        .findFirst();
  }

  public List<SalaryRecord> history(long empId) {
    return jdbc.query(
        "select "
            + COLUMNS
            + " from salary_records where emp_id = ?"
            + " order by effective_date desc, salary_id desc",
        SalaryRecordRepository::map,
        empId);
  }

  public Optional<SalaryRecord> findById(long salaryId) {
    return jdbc
        .query(
            "select " + COLUMNS + " from salary_records where salary_id = ?",
            SalaryRecordRepository::map,
            salaryId)
        .stream()
        .findFirst();
  }

  /**
   * Salary in force on {@code asOf} (PKG_PAYROLL.get_salary_as_of): latest effective_date not after
   * the date whose end_date is null or not before it. Exposed to other modules only through {@link
   * SalaryAsOfReader}.
   */
  public Optional<SalaryRecord> findEffectiveOn(long empId, LocalDate asOf) {
    return jdbc
        .query(
            "select "
                + COLUMNS
                + " from salary_records where emp_id = ? and effective_date <= ?"
                + " and (end_date is null or end_date >= ?)"
                + " order by effective_date desc, salary_id desc limit 1",
            SalaryRecordRepository::map,
            empId,
            asOf,
            asOf)
        .stream()
        .findFirst();
  }

  public int closeActive(long empId, LocalDate endDate, String user) {
    return jdbc.update(
        "update salary_records set end_date = ?, active_flag = 'N', modified_by = ?,"
            + " modified_date = current_timestamp where emp_id = ? and active_flag = 'Y'",
        endDate,
        user,
        empId);
  }

  public long insert(NewSalary salary) {
    Long id =
        jdbc.queryForObject(
            "insert into salary_records (salary_id, emp_id, effective_date, end_date,"
                + " base_salary, currency_code, pay_frequency, salary_basis, change_reason,"
                + " change_pct, active_flag, out_of_grade_band, created_by, created_date)"
                + " values (nextval('seq_salary'), ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Y', ?, ?,"
                + " current_timestamp) returning salary_id",
            Long.class,
            salary.empId(),
            salary.effectiveDate(),
            salary.endDate(),
            salary.baseSalary(),
            salary.currencyCode(),
            salary.payFrequency(),
            salary.salaryBasis(),
            salary.changeReason(),
            salary.changePct(),
            salary.outOfGradeBand() ? "Y" : "N",
            salary.createdBy());
    return id == null ? 0 : id;
  }

  public record NewSalary(
      long empId,
      LocalDate effectiveDate,
      LocalDate endDate,
      BigDecimal baseSalary,
      String currencyCode,
      String payFrequency,
      String salaryBasis,
      String changeReason,
      BigDecimal changePct,
      boolean outOfGradeBand,
      String createdBy) {}

  private static SalaryRecord map(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
    return new SalaryRecord(
        rs.getLong("salary_id"),
        rs.getLong("emp_id"),
        rs.getObject("effective_date", LocalDate.class),
        rs.getObject("end_date", LocalDate.class),
        rs.getBigDecimal("base_salary").setScale(2).toPlainString(),
        rs.getString("currency_code"),
        rs.getString("pay_frequency"),
        rs.getString("salary_basis"),
        rs.getString("change_reason"),
        nullableMoney(rs.getBigDecimal("change_pct")),
        "Y".equals(rs.getString("active_flag")),
        "Y".equals(rs.getString("out_of_grade_band")),
        rs.getString("created_by"),
        rs.getObject("created_date", LocalDateTime.class));
  }

  private static String nullableMoney(BigDecimal value) {
    return value == null ? null : value.setScale(2).toPlainString();
  }
}
