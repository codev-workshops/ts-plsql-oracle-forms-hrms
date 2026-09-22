package com.acme.hrms.common.format;

import java.math.BigDecimal;

/**
 * Renders a number the way Oracle's implicit {@code NUMBER -> VARCHAR2} conversion does: no
 * trailing zeros and no leading zero for a pure fraction.
 */
public final class OracleNumber {

  private OracleNumber() {}

  public static String render(BigDecimal value) {
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
