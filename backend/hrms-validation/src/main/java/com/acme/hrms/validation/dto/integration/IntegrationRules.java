package com.acme.hrms.validation.dto.integration;

import java.math.BigDecimal;

/** Frozen integration vocabulary (contracts/p5-reporting-decommission/openapi.yaml, BUG-08). */
public final class IntegrationRules {

  public static final BigDecimal HOURS_PER_DAY = new BigDecimal("24.00");
  public static final long UPLOAD_MAX_BYTES = 5L * 1024 * 1024;
  public static final int UPLOAD_MAX_LINES = 50_000;

  private IntegrationRules() {}
}
