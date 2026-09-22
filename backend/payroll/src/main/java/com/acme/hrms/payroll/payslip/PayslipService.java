package com.acme.hrms.payroll.payslip;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.payroll.PayrollDtos;
import com.acme.hrms.payroll.PayrollDtos.PayrollDetail;
import com.acme.hrms.payroll.PayrollDtos.Payslip;
import com.acme.hrms.payroll.period.PayPeriodRepository;
import com.acme.hrms.payroll.period.PayPeriodRepository.PeriodCore;
import com.acme.hrms.payroll.run.PayrollDetailRepository;
import com.acme.hrms.payroll.run.PayrollDetailRepository.Ytd;
import com.acme.hrms.payroll.run.PayrollRunRepository;
import com.acme.hrms.payroll.run.PayrollRunRepository.RunCore;
import com.acme.hrms.validation.dto.payroll.PayrollConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PKG_PAYROLL.get_payslip on PostgreSQL. Detail lines keep the stored sign; the aggregate fields
 * are positive magnitudes except {@code netPay}; YTD is the reporting YTD over APPROVED/PAID runs
 * (BUG-06 fixed).
 */
@Service
public class PayslipService {

  private final JdbcTemplate jdbc;
  private final PayrollRunRepository runs;
  private final PayPeriodRepository periods;
  private final PayrollDetailRepository details;

  public PayslipService(
      JdbcTemplate jdbc,
      PayrollRunRepository runs,
      PayPeriodRepository periods,
      PayrollDetailRepository details) {
    this.jdbc = jdbc;
    this.runs = runs;
    this.periods = periods;
    this.details = details;
  }

  record Subject(
      String empNumber,
      String empName,
      @Nullable String departmentName,
      @Nullable String jobTitle) {}

  @Transactional(readOnly = true)
  public Payslip get(long runId, long empId) {
    RunCore run = runs.core(runId).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
    Subject subject =
        subject(empId).orElseThrow(() -> new HrmsException(ErrorCode.EMPLOYEE_NOT_FOUND));
    PeriodCore period =
        periods.core(run.periodId()).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));

    List<PayrollDetail> lines = details.forEmployee(runId, empId);
    if (lines.isEmpty()) {
      throw new HrmsException(
          ErrorCode.PAYSLIP_NOT_FOUND,
          "Employee " + empId + " has no payslip in run " + runId,
          null);
    }
    List<PayrollDetail> calculated =
        lines.stream().filter(l -> !"ERROR".equals(l.status())).toList();
    if (calculated.isEmpty()) {
      PayrollDetail error = lines.get(0);
      throw new PayslipCalculationFailedException(error.errorCode(), error.errorMessage());
    }

    BigDecimal gross = sum(calculated, "EARNING", null);
    BigDecimal fed = magnitude(calculated, PayrollConstants.FED_TAX_ELEMENT_ID);
    BigDecimal state = magnitude(calculated, PayrollConstants.STATE_TAX_ELEMENT_ID);
    BigDecimal fica = magnitude(calculated, PayrollConstants.FICA_ELEMENT_ID);
    BigDecimal medicare = magnitude(calculated, PayrollConstants.MEDICARE_ELEMENT_ID);
    BigDecimal other =
        sum(calculated, "DEDUCTION", null).add(sum(calculated, "BENEFIT", null)).abs();
    BigDecimal totalDeductions = fed.add(state).add(fica).add(medicare).add(other);
    BigDecimal net =
        calculated.stream()
            .map(l -> new BigDecimal(l.amount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    Ytd ytd =
        details.reportingYtd(empId, period.periodStartDate().getYear(), period.periodEndDate());

    return new Payslip(
        runId,
        empId,
        subject.empNumber(),
        subject.empName(),
        subject.departmentName(),
        subject.jobTitle(),
        period.periodName(),
        period.periodStartDate(),
        period.periodEndDate(),
        period.payDate(),
        run.status(),
        PayrollDtos.money(gross),
        PayrollDtos.money(fed),
        PayrollDtos.money(state),
        PayrollDtos.money(fica),
        PayrollDtos.money(medicare),
        PayrollDtos.money(other),
        PayrollDtos.money(totalDeductions),
        PayrollDtos.money(net),
        PayrollDtos.money(ytd.gross()),
        PayrollDtos.money(ytd.taxes()),
        PayrollDtos.money(ytd.deductions()),
        PayrollDtos.money(ytd.net()),
        lines);
  }

  /** Any EMPLOYEES row, active or terminated (declared divergence from EmployeeLookup). */
  Optional<Subject> subject(long empId) {
    return jdbc
        .query(
            "select e.emp_number, e.first_name, e.last_name, d.dept_name, j.job_title"
                + " from employees e"
                + " left join departments d on d.dept_id = e.dept_id"
                + " left join job_titles j on j.job_id = e.job_id"
                + " where e.emp_id = ?",
            (rs, i) ->
                new Subject(
                    rs.getString("emp_number"),
                    rs.getString("first_name") + " " + rs.getString("last_name"),
                    rs.getString("dept_name"),
                    rs.getString("job_title")),
            empId)
        .stream()
        .findFirst();
  }

  private static BigDecimal sum(
      List<PayrollDetail> lines, String elementType, @Nullable Long elementId) {
    return lines.stream()
        .filter(l -> elementType.equals(l.elementType()))
        .filter(l -> elementId == null || l.elementId() == elementId)
        .map(l -> new BigDecimal(l.amount()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal magnitude(List<PayrollDetail> lines, long elementId) {
    return lines.stream()
        .filter(l -> l.elementId() == elementId)
        .map(l -> new BigDecimal(l.amount()))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .abs();
  }
}
