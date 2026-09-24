package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.dto.employee.WireDecimalDeserializer;

/**
 * {@code TaxBracketRequest.taxRate} of contracts/p5-reporting-decommission/openapi.yaml: a fraction
 * in {@code [0, 1]} as a JSON string with one to four decimals ({@code NUMERIC(5,4)}), never a JSON
 * number.
 */
public final class TaxRateDeserializer extends WireDecimalDeserializer {

  public static final String WIRE_PATTERN = "^(0\\.[0-9]{1,4}|1\\.0{1,4})$";
  public static final String MESSAGE =
      "Must be a decimal string fraction between 0 and 1 with one to four decimals";

  public TaxRateDeserializer() {
    super(WIRE_PATTERN, MESSAGE);
  }
}
