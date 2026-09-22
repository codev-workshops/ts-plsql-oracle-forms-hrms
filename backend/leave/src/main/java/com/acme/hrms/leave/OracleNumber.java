package com.acme.hrms.leave;

import java.math.BigDecimal;

/**
 * Renders a day count the way Oracle's implicit {@code NUMBER → VARCHAR2} conversion does inside
 * PKG_LEAVE messages: no trailing zeros ({@code 3}, {@code 7.5}) and no leading zero for a pure
 * fraction ({@code .5}), so {@code -20201} messages diff clean at Level 2.
 */
final class OracleNumber {

  private OracleNumber() {}

  static String render(BigDecimal value) {
    BigDecimal v = value.stripTrailingZeros();
    if (v.signum() == 0) {
      return "0";
    }
    String s = v.toPlainString();
    if (s.startsWith("0.")) {
      return s.substring(1);
    }
    if (s.startsWith("-0.")) {
      return "-" + s.substring(2);
    }
    return s;
  }
}
