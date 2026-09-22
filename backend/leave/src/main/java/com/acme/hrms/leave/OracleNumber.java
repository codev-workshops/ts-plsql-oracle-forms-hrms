package com.acme.hrms.leave;

import java.math.BigDecimal;

@Deprecated
final class OracleNumber {

  private OracleNumber() {}

  static String render(BigDecimal value) {
    return com.acme.hrms.common.format.OracleNumber.render(value);
  }
}
