package com.hrms.payroll;

import static com.hrms.payroll.PayrollModels.*;

import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.param.ParameterService;
import com.hrms.platform.salary.SalaryRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class PayrollCalculation {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private static final BigDecimal ALLOWANCE = new BigDecimal("4300");
    private static final Map<String, BigDecimal> STATE_RATES = Map.of(
            "CA", new BigDecimal("0.0725"), "NY", new BigDecimal("0.0685"),
            "TX", BigDecimal.ZERO, "FL", BigDecimal.ZERO, "WA", BigDecimal.ZERO,
            "IL", new BigDecimal("0.0495"), "PA", new BigDecimal("0.0307"),
            "OH", new BigDecimal("0.04"), "NJ", new BigDecimal("0.0637"), "MA", new BigDecimal("0.05"));

    private final ParameterService params;
    private final PayrollRepository repository;

    PayrollCalculation(ParameterService params, PayrollRepository repository) {
        this.params = params;
        this.repository = repository;
    }

    record Line(long elementId, ElementType type, BigDecimal amount) {}

    record TaxRules(Map<FilingStatus, List<TaxBracket>> federal, BigDecimal socialSecurityRate,
                    BigDecimal socialSecurityLimit, BigDecimal medicareRate, BigDecimal extraMedicareRate,
                    BigDecimal extraMedicareThreshold, BigDecimal defaultStateRate) {}

    TaxRules loadTaxRules() {
        int year = params.getInt("PAYROLL", "TAX_YEAR", 2024);
        Map<FilingStatus, List<TaxBracket>> brackets = new EnumMap<>(FilingStatus.class);
        for (TaxBracket bracket : repository.brackets(year, null)) {
            if (bracket.stateCode() == null) {
                brackets.computeIfAbsent(bracket.filingStatus(), key -> new ArrayList<>()).add(bracket);
            }
        }
        return new TaxRules(brackets, rate("FICA_RATE", "0.062"), rate("FICA_WAGE_BASE", "168600"),
                rate("MEDICARE_RATE", "0.0145"), rate("ADDL_MEDICARE_RATE", "0.009"),
                rate("ADDL_MEDICARE_THRESHOLD", "200000"), rate("STATE_TAX_RATE", "0.05"));
    }

    List<Line> calculate(SalaryRecord salary, Frequency frequency, TaxInfo w4, BigDecimal priorGross,
                         List<EmployeeElement> deductions, TaxRules rules) {
        BigDecimal periods = BigDecimal.valueOf(frequency.periodsPerYear);
        BigDecimal gross = salary.baseSalary().divide(periods, 2, RoundingMode.HALF_UP);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(1, ElementType.EARNING, gross));

        addCharge(lines, 100, ElementType.TAX, federalTax(gross, periods, w4, rules));
        if (w4 != null && w4.stateCode() != null && !w4.stateCode().isBlank()) {
            addCharge(lines, 101, ElementType.TAX, gross.multiply(
                    STATE_RATES.getOrDefault(w4.stateCode(), rules.defaultStateRate())));
        }

        BigDecimal remainingSocialSecurityWages = rules.socialSecurityLimit().subtract(priorGross).max(BigDecimal.ZERO);
        addCharge(lines, 102, ElementType.TAX, gross.min(remainingSocialSecurityWages).multiply(rules.socialSecurityRate()));

        BigDecimal remainingMedicareWages = rules.extraMedicareThreshold().subtract(priorGross).max(BigDecimal.ZERO);
        BigDecimal extraMedicareWages = gross.subtract(remainingMedicareWages).max(BigDecimal.ZERO);
        addCharge(lines, 103, ElementType.TAX, money(gross.multiply(rules.medicareRate()))
                .add(money(extraMedicareWages.multiply(rules.extraMedicareRate()))));

        for (EmployeeElement element : deductions) {
            addCharge(lines, element.elementId(), ElementType.valueOf(element.elementType()), deduction(element, gross));
        }
        return lines;
    }

    private static BigDecimal federalTax(BigDecimal gross, BigDecimal periods, TaxInfo w4, TaxRules rules) {
        FilingStatus filingStatus = w4 == null ? FilingStatus.SINGLE : w4.filingStatus();
        int allowances = w4 == null ? 0 : w4.federalAllowances();
        BigDecimal deduction = BigDecimal.valueOf(filingStatus == FilingStatus.MARRIED_JOINT ? 29200 : 14600);
        BigDecimal taxable = gross.multiply(periods).subtract(deduction)
                .subtract(ALLOWANCE.multiply(BigDecimal.valueOf(allowances)));
        if (taxable.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        TaxBracket bracket = rules.federal().getOrDefault(filingStatus, List.of()).stream()
                .filter(b -> taxable.compareTo(b.bracketMin()) >= 0
                        && (b.bracketMax() == null || taxable.compareTo(b.bracketMax()) < 0))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION,
                        "No configured federal tax bracket for " + filingStatus));
        BigDecimal annualTax = zeroIfNull(bracket.baseTax())
                .add(taxable.subtract(bracket.bracketMin()).multiply(bracket.taxRate()));
        BigDecimal additional = w4 == null ? BigDecimal.ZERO : zeroIfNull(w4.additionalFedWh());
        return annualTax.divide(periods, 2, RoundingMode.HALF_UP).add(additional);
    }

    private static BigDecimal deduction(EmployeeElement element, BigDecimal gross) {
        if (element.overrideAmount() != null) {
            return element.overrideAmount();
        }
        return switch (element.calculationType()) {
            case "FLAT" -> element.amount() == null ? element.defaultAmount() : element.amount();
            case "PERCENTAGE" -> gross.multiply(zeroIfNull(element.percentage() == null
                    ? element.defaultPercentage() : element.percentage())).movePointLeft(2);
            default -> element.amount();
        };
    }

    private static void addCharge(List<Line> lines, long id, ElementType type, BigDecimal amount) {
        BigDecimal rounded = money(zeroIfNull(amount));
        if (rounded.compareTo(MAX_AMOUNT) > 0) {
            throw new ApiException(ErrorCode.VALIDATION, "Payroll charge exceeds the supported amount for element " + id);
        }
        if (rounded.signum() > 0) {
            lines.add(new Line(id, type, rounded.negate()));
        }
    }

    private BigDecimal rate(String name, String fallback) {
        return params.getDecimal("PAYROLL", name, new BigDecimal(fallback));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
