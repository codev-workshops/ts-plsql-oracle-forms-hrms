package com.hrms.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PKG_PAYROLL.calculate_federal_tax / calculate_state_tax / calculate_fica / calculate_medicare (PKG-P05). The
 * formulas and rounding are ported as is; the brackets and constants come from {@code tax_brackets} and
 * {@code payroll_tax_parameters} instead of code (DEF-05 fix), using the latest tax year not after the
 * requested one.
 */
@Component
public class TaxCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final JdbcClient jdbc;

    public TaxCalculator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Bracket(String filingStatus, BigDecimal min, BigDecimal max, BigDecimal rate, BigDecimal baseTax) {
    }

    /** The tax configuration effective for one tax year. */
    public record TaxTables(int taxYear, List<Bracket> federalBrackets, Map<String, BigDecimal> parameters,
            Map<String, BigDecimal> stateRates) {

        BigDecimal param(String code) {
            BigDecimal v = parameters.get(code);
            if (v == null) {
                throw new IllegalStateException("Payroll tax parameter " + code + " missing for " + taxYear);
            }
            return v;
        }
    }

    /** Loads the tables effective for {@code year}: the latest configured tax year that is not after it. */
    public TaxTables tablesFor(int year) {
        Integer taxYear = jdbc.sql("SELECT max(tax_year) FROM hrms.payroll_tax_parameters WHERE tax_year <= :y")
                .param("y", year).query(Integer.class).optional().orElse(null);
        if (taxYear == null) {
            throw new IllegalStateException("No payroll tax parameters configured for " + year + " or earlier");
        }
        Integer bracketYear = jdbc.sql("""
                SELECT max(tax_year) FROM hrms.tax_brackets WHERE tax_year <= :y AND state_code IS NULL AND active_flag
                """).param("y", year).query(Integer.class).optional().orElse(null);
        List<Bracket> brackets = bracketYear == null ? List.of() : jdbc.sql("""
                SELECT filing_status, bracket_min, bracket_max, tax_rate, coalesce(base_tax, 0)
                FROM hrms.tax_brackets WHERE tax_year = :y AND state_code IS NULL AND active_flag
                ORDER BY filing_status, bracket_min
                """).param("y", bracketYear).query((rs, i) -> new Bracket(rs.getString(1), rs.getBigDecimal(2),
                rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5))).list();
        Map<String, BigDecimal> params = new java.util.HashMap<>();
        Map<String, BigDecimal> states = new java.util.HashMap<>();
        jdbc.sql("SELECT param_code, state_code, param_value FROM hrms.payroll_tax_parameters WHERE tax_year = :y")
                .param("y", taxYear).query((rs, i) -> {
                    if (rs.getString(2) != null) {
                        states.put(rs.getString(2), rs.getBigDecimal(3));
                    } else {
                        params.put(rs.getString(1), rs.getBigDecimal(3));
                    }
                    return null;
                }).list();
        return new TaxTables(taxYear, brackets, Map.copyOf(params), Map.copyOf(states));
    }

    /**
     * calculate_federal_tax: annualize, subtract the standard deduction (MARRIED_JOINT or the single amount for
     * every other status) and allowances, apply the bracket, de-annualize (rounded to cents), add the additional
     * withholding. A non-positive annual taxable amount is 0 with no additional withholding (legacy). A filing
     * status without brackets is 0 plus the additional withholding (legacy HEAD_OF_HOUSEHOLD, DEF-P6-4).
     */
    public static BigDecimal federalTax(TaxTables t, BigDecimal periodTaxable, String filingStatus,
            int allowances, BigDecimal additionalWithholding, int periodsPerYear) {
        BigDecimal periods = BigDecimal.valueOf(periodsPerYear);
        BigDecimal stdDeduction = "MARRIED_JOINT".equals(filingStatus) ? t.param("STD_DEDUCTION_MARRIED_JOINT")
                : t.param("STD_DEDUCTION_SINGLE");
        BigDecimal taxable = periodTaxable.multiply(periods).subtract(stdDeduction)
                .subtract(t.param("ALLOWANCE_AMOUNT").multiply(BigDecimal.valueOf(allowances)));
        if (taxable.signum() <= 0) {
            return ZERO.setScale(2);
        }
        BigDecimal annualTax = bracket(t, filingStatus, taxable)
                .map(b -> b.baseTax().add(taxable.subtract(b.min()).multiply(b.rate()))).orElse(ZERO);
        BigDecimal extra = additionalWithholding == null ? ZERO : additionalWithholding;
        return annualTax.divide(periods, 2, RoundingMode.HALF_UP).add(extra);
    }

    private static Optional<Bracket> bracket(TaxTables t, String filingStatus, BigDecimal taxable) {
        return t.federalBrackets().stream()
                .filter(b -> b.filingStatus().equals(filingStatus) && taxable.compareTo(b.min()) > 0
                        && (b.max() == null || taxable.compareTo(b.max()) <= 0))
                .findFirst();
    }

    /** calculate_state_tax: a flat rate per state, the default rate for unknown states; rounded to cents. */
    public static BigDecimal stateTax(TaxTables t, BigDecimal periodTaxable, String stateCode) {
        BigDecimal rate = t.stateRates().getOrDefault(stateCode, t.param("STATE_DEFAULT_RATE"));
        return periodTaxable.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** calculate_fica: Social Security up to the wage base, given the prior year-to-date gross. */
    public static BigDecimal socialSecurity(TaxTables t, BigDecimal gross, BigDecimal ytdGross) {
        BigDecimal base = t.param("SS_WAGE_BASE");
        if (ytdGross.compareTo(base) >= 0) {
            return ZERO.setScale(2);
        }
        return gross.min(base.subtract(ytdGross)).multiply(t.param("SS_RATE")).setScale(2, RoundingMode.HALF_UP);
    }

    /** calculate_medicare: the base rate plus the additional rate on year-to-date gross above the threshold. */
    public static BigDecimal medicare(TaxTables t, BigDecimal gross, BigDecimal ytdGross) {
        BigDecimal baseTax = gross.multiply(t.param("MEDICARE_RATE")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal threshold = t.param("MEDICARE_ADDL_THRESHOLD");
        BigDecimal addlRate = t.param("MEDICARE_ADDL_RATE");
        BigDecimal addl = ZERO;
        if (ytdGross.add(gross).compareTo(threshold) > 0) {
            addl = ytdGross.compareTo(threshold) >= 0
                    ? gross.multiply(addlRate).setScale(2, RoundingMode.HALF_UP)
                    : ytdGross.add(gross).subtract(threshold).multiply(addlRate).setScale(2, RoundingMode.HALF_UP);
        }
        return baseTax.add(addl);
    }
}
