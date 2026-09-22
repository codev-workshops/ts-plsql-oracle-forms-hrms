package com.acme.hrms.payroll.tax;

import java.math.BigDecimal;
import org.springframework.lang.Nullable;

/** One TAX_BRACKETS row. {@code stateCode == null} = federal ladder; {@code max == null} = top. */
public record TaxBracket(
    int taxYear,
    String filingStatus,
    BigDecimal min,
    @Nullable BigDecimal max,
    BigDecimal rate,
    BigDecimal baseTax,
    @Nullable String stateCode) {

  public boolean contains(BigDecimal taxable) {
    return taxable.compareTo(min) >= 0 && (max == null || taxable.compareTo(max) < 0);
  }
}
