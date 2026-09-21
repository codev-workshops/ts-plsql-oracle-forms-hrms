package com.acme.hrms.validation.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Frozen reverse-proxy flag names (contracts/p0-foundation/README.md). */
public enum ProxyModule {
  AUTH("auth"),
  EMPLOYEE("employee"),
  PAYROLL("payroll"),
  PAYROLL_ENGINE("payroll.engine"),
  LEAVE("leave"),
  PERFORMANCE("performance"),
  REPORTING("reporting");

  private final String wire;

  ProxyModule(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }

  @JsonCreator
  public static ProxyModule fromWire(String value) {
    for (ProxyModule m : values()) {
      if (m.wire.equals(value)) {
        return m;
      }
    }
    throw new IllegalArgumentException("Unknown module " + value);
  }
}
