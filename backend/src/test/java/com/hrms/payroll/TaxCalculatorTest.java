package com.hrms.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrms.payroll.TaxCalculator.Bracket;
import com.hrms.payroll.TaxCalculator.TaxTables;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Parity of the PKG_PAYROLL tax formulas (PKG-P05) with the 2024 values, and of create_pay_periods. */
class TaxCalculatorTest {

    private static final TaxTables T2024 = new TaxTables(2024, List.of(
            b("SINGLE", 0, 11600, "0.10", "0"), b("SINGLE", 11600, 47150, "0.12", "1160"),
            b("SINGLE", 47150, 100525, "0.22", "5426"), b("SINGLE", 100525, 191950, "0.24", "17168.50"),
            b("SINGLE", 191950, 243725, "0.32", "39110.50"), b("SINGLE", 243725, 609350, "0.35", "55678.50"),
            b("SINGLE", 609350, null, "0.37", "183647.25")),
            Map.of("SS_WAGE_BASE", bd("168600"), "SS_RATE", bd("0.062"), "MEDICARE_RATE", bd("0.0145"),
                    "MEDICARE_ADDL_RATE", bd("0.009"), "MEDICARE_ADDL_THRESHOLD", bd("200000"),
                    "STD_DEDUCTION_SINGLE", bd("14600"), "STD_DEDUCTION_MARRIED_JOINT", bd("29200"),
                    "ALLOWANCE_AMOUNT", bd("4300"), "STATE_DEFAULT_RATE", bd("0.05")),
            Map.of("CA", bd("0.0725"), "TX", bd("0")));

    @Test
    void federalTaxFollowsTheLegacyFormula() {
        // 6500 * 12 - 14600 = 63400 -> 5426 + 22% * 16250 = 9001 per year -> 750.08
        assertThat(TaxCalculator.federalTax(T2024, bd("6500.00"), "SINGLE", 0, bd("0"), 12)).isEqualByComparingTo("750.08");
        // the top bracket has no upper bound: 75000 * 12 - 14600 = 885400 -> 183647.25 + 37% * 276050
        assertThat(TaxCalculator.federalTax(T2024, bd("75000"), "SINGLE", 0, null, 12)).isEqualByComparingTo("23815.48");
        // non-positive taxable income: 0 and no additional withholding (legacy returned before adding it)
        assertThat(TaxCalculator.federalTax(T2024, bd("1000"), "SINGLE", 0, bd("50"), 12)).isEqualByComparingTo("0");
        // a status without brackets (HEAD_OF_HOUSEHOLD in legacy): 0 plus the additional withholding (DEF-P6-4)
        assertThat(TaxCalculator.federalTax(T2024, bd("6500"), "HEAD_OF_HOUSEHOLD", 0, bd("10"), 12))
                .isEqualByComparingTo("10");
        // allowances reduce the annual taxable amount by 4300 each
        assertThat(TaxCalculator.federalTax(T2024, bd("6500.00"), "SINGLE", 1, bd("0"), 12)).isEqualByComparingTo("671.25");
    }

    @Test
    void stateFicaAndMedicare() {
        assertThat(TaxCalculator.stateTax(T2024, bd("6500"), "CA")).isEqualByComparingTo("471.25");
        assertThat(TaxCalculator.stateTax(T2024, bd("6500"), "TX")).isEqualByComparingTo("0");
        assertThat(TaxCalculator.stateTax(T2024, bd("6500"), "ZZ")).isEqualByComparingTo("325.00");
        assertThat(TaxCalculator.socialSecurity(T2024, bd("10000"), bd("0"))).isEqualByComparingTo("620.00");
        assertThat(TaxCalculator.socialSecurity(T2024, bd("10000"), bd("165000"))).isEqualByComparingTo("223.20");
        assertThat(TaxCalculator.socialSecurity(T2024, bd("10000"), bd("168600"))).isEqualByComparingTo("0");
        assertThat(TaxCalculator.medicare(T2024, bd("10000"), bd("0"))).isEqualByComparingTo("145.00");
        assertThat(TaxCalculator.medicare(T2024, bd("10000"), bd("195000"))).isEqualByComparingTo("190.00");
        assertThat(TaxCalculator.medicare(T2024, bd("10000"), bd("200000"))).isEqualByComparingTo("235.00");
    }

    @Test
    void payPeriodGeneration() {
        List<PayPeriodService.PeriodSpec> m = PayPeriodService.monthly(2024);
        assertThat(m).hasSize(12);
        assertThat(m.get(1).name()).isEqualTo("2024-02 (Feb)");
        assertThat(m.get(1).end()).hasToString("2024-02-29");
        assertThat(m.get(5).payDate()).hasToString("2024-06-28"); // Sunday 30 June -> Friday
        assertThat(m.get(7).payDate()).hasToString("2024-08-30"); // Saturday 31 August -> Friday
        List<PayPeriodService.PeriodSpec> bw = PayPeriodService.biweekly(2024);
        assertThat(bw.get(0).start()).hasToString("2023-12-23"); // 5 January 2024 - 13
        assertThat(bw.get(0).payDate()).hasToString("2024-01-10");
        assertThat(bw).allSatisfy(p -> assertThat(p.end()).isEqualTo(p.start().plusDays(13)));
        assertThat(bw.get(bw.size() - 1).start().getYear()).isEqualTo(2024);
        assertThat(bw.get(bw.size() - 1).name()).isEqualTo("2024-BW-%02d".formatted(bw.size()));
    }

    private static Bracket b(String status, int min, Integer max, String rate, String base) {
        return new Bracket(status, BigDecimal.valueOf(min), max == null ? null : BigDecimal.valueOf(max), bd(rate), bd(base));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
