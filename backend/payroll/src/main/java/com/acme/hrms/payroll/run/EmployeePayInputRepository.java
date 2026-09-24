package com.acme.hrms.payroll.run;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Read-only inputs of calculate_employee_pay: eligible employees, W-4 info, recurring elements. */
@Repository
public class EmployeePayInputRepository {

  private final JdbcTemplate jdbc;

  public EmployeePayInputRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** EMPLOYMENT_STATUS='ACTIVE' AND ACTIVE_FLAG='Y', ascending emp_id (legacy cursor order). */
  public List<Long> eligibleEmployeeIds() {
    return jdbc.queryForList(
        "select emp_id from employees where employment_status = 'ACTIVE' and active_flag = 'Y'"
            + " order by emp_id",
        Long.class);
  }

  public boolean employeeExists(long empId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from employees where emp_id = ?", Integer.class, empId);
    return n != null && n > 0;
  }

  public record TaxInfo(
      String filingStatus,
      int federalAllowances,
      @Nullable String stateCode,
      BigDecimal additionalFedWithholding) {
    public static final TaxInfo DEFAULT = new TaxInfo("SINGLE", 0, null, BigDecimal.ZERO);
  }

  public TaxInfo taxInfo(long empId, int taxYear) {
    return jdbc
        .query(
            "select filing_status, federal_allowances, state_code, additional_fed_wh"
                + " from employee_tax_info where emp_id = ? and tax_year = ? and active_flag = 'Y'"
                + " order by tax_info_id desc limit 1",
            (rs, i) ->
                new TaxInfo(
                    rs.getString("filing_status"),
                    rs.getInt("federal_allowances"),
                    rs.getString("state_code"),
                    Optional.ofNullable(rs.getBigDecimal("additional_fed_wh"))
                        .orElse(BigDecimal.ZERO)),
            empId,
            taxYear)
        .stream()
        .findFirst()
        .orElse(TaxInfo.DEFAULT);
  }

  public record RecurringElement(
      long elementId,
      String elementType,
      String calculationType,
      @Nullable BigDecimal amount,
      @Nullable BigDecimal percentage,
      @Nullable BigDecimal overrideAmount,
      @Nullable BigDecimal defaultAmount,
      @Nullable BigDecimal defaultPercentage) {}

  public List<RecurringElement> recurringElements(
      long empId, LocalDate periodStart, LocalDate periodEnd) {
    return jdbc.query(
        "select epe.element_id, pe.element_type, pe.calculation_type, epe.amount, epe.percentage,"
            + " epe.override_amount, pe.default_amount, pe.default_percentage"
            + " from employee_pay_elements epe join pay_elements pe on pe.element_id = epe.element_id"
            + " where epe.emp_id = ? and epe.active_flag = 'Y'"
            + " and pe.element_type in ('EARNING', 'DEDUCTION', 'BENEFIT')"
            + " and epe.element_id <> 1"
            + " and epe.effective_date <= ? and (epe.end_date is null or epe.end_date >= ?)"
            + " order by pe.priority_order, epe.element_id",
        (rs, i) ->
            new RecurringElement(
                rs.getLong("element_id"),
                rs.getString("element_type"),
                rs.getString("calculation_type"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("percentage"),
                rs.getBigDecimal("override_amount"),
                rs.getBigDecimal("default_amount"),
                rs.getBigDecimal("default_percentage")),
        empId,
        periodEnd,
        periodStart);
  }
}
