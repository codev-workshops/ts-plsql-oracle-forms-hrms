package com.acme.hrms.payroll.run;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.payroll.period.PayPeriodRepository.PeriodCore;
import com.acme.hrms.payroll.run.EmployeePayInputRepository.RecurringElement;
import com.acme.hrms.payroll.run.EmployeePayInputRepository.TaxInfo;
import com.acme.hrms.payroll.run.PayrollDetailRepository.NewDetail;
import com.acme.hrms.payroll.tax.TaxEngine;
import com.acme.hrms.payroll.tax.TaxRules;
import com.acme.hrms.salary.SalaryDtos.SalaryRecord;
import com.acme.hrms.salary.SalaryRecordRepository;
import com.acme.hrms.validation.dto.payroll.PayrollConstants;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * calculate_employee_pay for one employee: produces the full signed row set, or a single sentinel
 * ERROR row when anything fails (the run continues). Nothing is written here.
 */
@Component
public class EmployeePayCalculator {

  private static final Logger log = LoggerFactory.getLogger(EmployeePayCalculator.class);
  private static final int MESSAGE_MAX = 4000;

  private final SalaryRecordRepository salaries;
  private final EmployeePayInputRepository inputs;
  private final PayrollDetailRepository details;
  private final TaxEngine taxEngine;

  public EmployeePayCalculator(
      SalaryRecordRepository salaries,
      EmployeePayInputRepository inputs,
      PayrollDetailRepository details,
      TaxEngine taxEngine) {
    this.salaries = salaries;
    this.inputs = inputs;
    this.details = details;
    this.taxEngine = taxEngine;
  }

  public List<NewDetail> calculate(
      long runId, PeriodCore period, TaxRules rules, long empId, String user) {
    try {
      return rows(runId, period, rules, empId, user);
    } catch (HrmsException e) {
      return List.of(errorRow(runId, empId, e.code().value(), e.getMessage(), user));
    } catch (RuntimeException e) {
      log.error("payroll run {} emp {}: unexpected failure", runId, empId, e);
      return List.of(
          errorRow(
              runId,
              empId,
              ErrorCode.INTERNAL_ERROR.value(),
              ErrorCode.INTERNAL_ERROR.defaultMessage(),
              user));
    }
  }

  private List<NewDetail> rows(
      long runId, PeriodCore period, TaxRules rules, long empId, String user) {
    BigDecimal annual =
        salaries
            .findEffectiveOn(empId, period.periodEndDate())
            .map(SalaryRecord::baseSalary)
            .map(BigDecimal::new)
            .orElse(BigDecimal.ZERO);
    if (annual.signum() == 0) {
      throw new HrmsException(
          ErrorCode.NO_ACTIVE_SALARY, "No active salary record for employee " + empId, null);
    }
    BigDecimal gross = taxEngine.periodGross(annual, period.payFrequency());
    BigDecimal ytdGross = details.taxYtdGross(empId, period.taxYear()).add(gross);
    TaxInfo w4 = inputs.taxInfo(empId, period.taxYear());
    TaxEngine.Result taxes =
        taxEngine.calculate(
            rules,
            new TaxEngine.Input(
                annual,
                period.payFrequency(),
                w4.filingStatus(),
                w4.federalAllowances(),
                w4.additionalFedWithholding(),
                w4.stateCode(),
                ytdGross));

    List<NewDetail> out = new ArrayList<>();
    BigDecimal reportingYtd =
        details.reportingYtd(empId, period.taxYear(), period.periodEndDate()).gross().add(gross);
    out.add(
        detail(
            runId,
            empId,
            PayrollConstants.BASE_PAY_ELEMENT_ID,
            "EARNING",
            gross,
            reportingYtd,
            user));
    tax(out, runId, empId, PayrollConstants.FED_TAX_ELEMENT_ID, taxes.federalTax(), user);
    tax(out, runId, empId, PayrollConstants.STATE_TAX_ELEMENT_ID, taxes.stateTax(), user);
    tax(out, runId, empId, PayrollConstants.FICA_ELEMENT_ID, taxes.socialSecurity(), user);
    tax(out, runId, empId, PayrollConstants.MEDICARE_ELEMENT_ID, taxes.medicare(), user);

    for (RecurringElement el :
        inputs.recurringElements(empId, period.periodStartDate(), period.periodEndDate())) {
      BigDecimal amount = recurringAmount(el, gross);
      if (amount.signum() > 0) {
        BigDecimal signed = "EARNING".equals(el.elementType()) ? amount : amount.negate();
        out.add(detail(runId, empId, el.elementId(), el.elementType(), signed, null, user));
      }
    }
    return out;
  }

  static BigDecimal recurringAmount(RecurringElement el, BigDecimal gross) {
    if (el.overrideAmount() != null) {
      return el.overrideAmount();
    }
    return switch (el.calculationType()) {
      case "FLAT" -> Optional.ofNullable(el.amount()).orElse(zero(el.defaultAmount()));
      case "PERCENTAGE" -> {
        BigDecimal pct = Optional.ofNullable(el.percentage()).orElse(zero(el.defaultPercentage()));
        yield gross.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      }
      default -> zero(el.amount());
    };
  }

  private static BigDecimal zero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static void tax(
      List<NewDetail> out, long runId, long empId, long elementId, BigDecimal amount, String user) {
    if (amount.signum() > 0) {
      out.add(detail(runId, empId, elementId, "TAX", amount.negate(), null, user));
    }
  }

  private static NewDetail detail(
      long runId,
      long empId,
      long elementId,
      String type,
      BigDecimal amount,
      BigDecimal ytd,
      String user) {
    return new NewDetail(
        runId,
        empId,
        elementId,
        type,
        TaxEngine.round2(amount),
        ytd,
        "CALCULATED",
        null,
        null,
        user);
  }

  public static NewDetail errorRow(
      long runId, long empId, String code, String message, String user) {
    String msg = message == null ? code : message;
    return new NewDetail(
        runId,
        empId,
        PayrollConstants.ERROR_ELEMENT_ID,
        "ERROR",
        new BigDecimal("0.00"),
        null,
        "ERROR",
        code,
        msg.length() > MESSAGE_MAX ? msg.substring(0, MESSAGE_MAX) : msg,
        user);
  }
}
