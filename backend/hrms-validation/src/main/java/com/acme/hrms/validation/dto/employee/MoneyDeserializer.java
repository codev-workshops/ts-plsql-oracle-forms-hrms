package com.acme.hrms.validation.dto.employee;

/**
 * {@code Money} of contracts/p3-employee/openapi.yaml: a JSON string matching {@link #WIRE_PATTERN}
 * (never a JSON number). See {@link WireDecimalDeserializer} for the malformed-property handling.
 */
public final class MoneyDeserializer extends WireDecimalDeserializer {

  public static final String WIRE_PATTERN = "^-?[0-9]+\\.[0-9]{2}$";
  public static final String MESSAGE = "Must be a decimal string with exactly two decimals";

  public MoneyDeserializer() {
    super(WIRE_PATTERN, MESSAGE);
  }
}
