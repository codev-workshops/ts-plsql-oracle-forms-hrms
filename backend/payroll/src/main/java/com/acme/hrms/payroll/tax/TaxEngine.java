package com.acme.hrms.payroll.tax;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Stateless port of PKG_PAYROLL.calculate_employee_pay's tax arithmetic, reading every rate and
 * threshold from {@link TaxRules} instead of the hard-coded 2024 constants. Rounding points are the
 * legacy ones: gross, each tax, once.
 */
@Component
public class TaxEngine {

  public static final Map<String, Integer> PERIODS_PER_YEAR =
      Map.of("WEEKLY", 52, "BIWEEKLY", 26, "SEMIMONTHLY", 24, "MONTHLY", 12);
  private static final int DEFAULT_PERIODS = 12;

  /** Employee-side inputs; {@code ytdGross} is TAX-YTD-01 (already includes this gross). */
  public record Input(
      BigDecimal annualSalary,
      String payFrequency,
      String filingStatus,
      int federalAllowances,
      BigDecimal additionalFedWithholding,
      @Nullable String stateCode,
      BigDecimal ytdGross) {

    public static Input defaults(
        BigDecimal annualSalary, String payFrequency, BigDecimal ytdGross) {
      return new Input(annualSalary, payFrequency, "SINGLE", 0, BigDecimal.ZERO, null, ytdGross);
    }
  }

  /** Positive magnitudes; zero taxes mean "no row". */
  public record Result(
      BigDecimal periodGross,
      BigDecimal federalTax,
      BigDecimal stateTax,
      BigDecimal socialSecurity,
      BigDecimal medicare) {}

  public static int periodsPerYear(@Nullable String payFrequency) {
    return payFrequency == null
        ? DEFAULT_PERIODS
        : PERIODS_PER_YEAR.getOrDefault(payFrequency, DEFAULT_PERIODS);
  }

  /** {@code ROUND(annual / divisor, 2)}; salaries that are not positive are -20101. */
  public BigDecimal periodGross(BigDecimal annualSalary, @Nullable String payFrequency) {
    if (annualSalary == null || annualSalary.signum() <= 0) {
      throw new HrmsException(
          ErrorCode.SALARY_NOT_POSITIVE, ErrorCode.SALARY_NOT_POSITIVE.defaultMessage(), null);
    }
    return annualSalary.divide(
        BigDecimal.valueOf(periodsPerYear(payFrequency)), 2, RoundingMode.HALF_UP);
  }

  public Result calculate(TaxRules rules, Input in) {
    BigDecimal gross = periodGross(in.annualSalary(), in.payFrequency());
    BigDecimal ytd = in.ytdGross() == null ? gross : in.ytdGross();
    BigDecimal federal =
        federalTax(
            rules,
            gross,
            in.filingStatus(),
            in.federalAllowances(),
            in.additionalFedWithholding(),
            in.payFrequency());
    BigDecimal state =
        in.stateCode() == null ? round2(BigDecimal.ZERO) : stateTax(rules, gross, in.stateCode());
    return new Result(
        gross, federal, state, socialSecurity(rules, gross, ytd), medicare(rules, gross, ytd));
  }

  public BigDecimal federalTax(
      TaxRules rules,
      BigDecimal periodGross,
      String filingStatus,
      int allowances,
      @Nullable BigDecimal additionalWithholding,
      @Nullable String payFrequency) {
    int periods = periodsPerYear(payFrequency);
    List<TaxBracket> ladder =
        rules
            .federalLadder(filingStatus)
            .orElseThrow(() -> new MissingTaxRateException(rules.taxYear(), filingStatus));
    BigDecimal stdDeduction =
        rules
            .optionalScalar("STD_DEDUCTION." + filingStatus)
            .orElseThrow(() -> new MissingTaxRateException(rules.taxYear(), filingStatus));
    BigDecimal annualized = periodGross.multiply(BigDecimal.valueOf(periods));
    BigDecimal taxable =
        annualized
            .subtract(stdDeduction)
            .subtract(rules.scalar("ALLOWANCE").multiply(BigDecimal.valueOf(allowances)));
    BigDecimal annualTax = BigDecimal.ZERO;
    if (taxable.signum() > 0) {
      TaxBracket bracket =
          ladder.stream()
              .filter(b -> b.contains(taxable))
              .findFirst()
              .orElseThrow(() -> new MissingTaxRateException(rules.taxYear(), filingStatus));
      annualTax = bracket.baseTax().add(taxable.subtract(bracket.min()).multiply(bracket.rate()));
    }
    BigDecimal periodTax = annualTax.divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP);
    return periodTax
        .add(additionalWithholding == null ? BigDecimal.ZERO : additionalWithholding)
        .setScale(2, RoundingMode.HALF_UP);
  }

  public BigDecimal stateTax(TaxRules rules, BigDecimal periodGross, String stateCode) {
    TaxBracket row =
        rules
            .stateRate(stateCode)
            .orElseThrow(() -> new MissingTaxRateException(rules.taxYear(), stateCode));
    return round2(periodGross.multiply(row.rate()));
  }

  public BigDecimal socialSecurity(TaxRules rules, BigDecimal periodGross, BigDecimal ytdGross) {
    BigDecimal wageBase = rules.scalar("SS_WAGE_BASE");
    BigDecimal room = wageBase.subtract(ytdGross).max(BigDecimal.ZERO);
    BigDecimal taxable = periodGross.min(room);
    return round2(taxable.multiply(rules.scalar("SS_RATE")));
  }

  public BigDecimal medicare(TaxRules rules, BigDecimal periodGross, BigDecimal ytdGross) {
    BigDecimal base = round2(periodGross.multiply(rules.scalar("MEDICARE_RATE")));
    BigDecimal threshold = rules.scalar("MEDICARE_ADDL_THRESHOLD");
    BigDecimal addl = BigDecimal.ZERO;
    if (ytdGross.add(periodGross).compareTo(threshold) > 0) {
      BigDecimal addlBase =
          ytdGross.compareTo(threshold) >= 0
              ? periodGross
              : ytdGross.add(periodGross).subtract(threshold);
      addl = round2(addlBase.multiply(rules.scalar("MEDICARE_ADDL_RATE")));
    }
    return base.add(addl);
  }

  public static BigDecimal round2(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
