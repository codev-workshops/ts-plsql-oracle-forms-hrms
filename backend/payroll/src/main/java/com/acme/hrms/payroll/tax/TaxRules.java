package com.acme.hrms.payroll.tax;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of one tax year's TAX_BRACKETS rows and {@code TAX.<year>.*} scalars, the only
 * input {@link TaxEngine} needs besides the employee figures.
 */
public record TaxRules(
    int taxYear,
    Map<String, List<TaxBracket>> federalLadders,
    Map<String, TaxBracket> stateRates,
    Map<String, BigDecimal> scalars) {

  public static final String STATE_FILING_STATUS = "ALL";

  public static TaxRules of(
      int taxYear, List<TaxBracket> brackets, Map<String, BigDecimal> scalars) {
    Map<String, List<TaxBracket>> federal =
        brackets.stream()
            .filter(b -> b.stateCode() == null)
            .sorted(Comparator.comparing(TaxBracket::min))
            .collect(Collectors.groupingBy(TaxBracket::filingStatus));
    Map<String, TaxBracket> state = new HashMap<>();
    brackets.stream()
        .filter(b -> b.stateCode() != null && STATE_FILING_STATUS.equals(b.filingStatus()))
        .forEach(b -> state.putIfAbsent(b.stateCode(), b));
    return new TaxRules(taxYear, Map.copyOf(federal), Map.copyOf(state), Map.copyOf(scalars));
  }

  public Optional<List<TaxBracket>> federalLadder(String filingStatus) {
    return Optional.ofNullable(federalLadders.get(filingStatus));
  }

  public Optional<TaxBracket> stateRate(String stateCode) {
    return Optional.ofNullable(stateRates.get(stateCode));
  }

  /** {@code <year>.NAME} scalar; absent parameters are a configuration error, not a tax rule. */
  public BigDecimal scalar(String name) {
    String key = taxYear + "." + name;
    BigDecimal value = scalars.get(key);
    if (value == null) {
      throw new IllegalStateException("Missing SYSTEM_PARAMETERS TAX." + key);
    }
    return value;
  }

  public Optional<BigDecimal> optionalScalar(String name) {
    return Optional.ofNullable(scalars.get(taxYear + "." + name));
  }
}
