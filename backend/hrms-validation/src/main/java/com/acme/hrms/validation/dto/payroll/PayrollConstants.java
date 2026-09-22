package com.acme.hrms.validation.dto.payroll;

import java.util.List;

/**
 * Frozen Payroll vocabulary (contracts/p4-payroll/README.md). Tax element ids are the PAY_ELEMENTS
 * rows the TaxEngine writes; the backend validates them against the table at startup.
 */
public final class PayrollConstants {

  public static final long BASE_PAY_ELEMENT_ID = 1L;
  public static final long FED_TAX_ELEMENT_ID = 100L;
  public static final long STATE_TAX_ELEMENT_ID = 101L;
  public static final long FICA_ELEMENT_ID = 102L;
  public static final long MEDICARE_ELEMENT_ID = 103L;

  /** Sentinel PAY_ELEMENTS row referenced by PAYROLL_DETAILS.STATUS='ERROR' rows. */
  public static final long ERROR_ELEMENT_ID = 0L;

  public static final List<String> PERIOD_STATUSES =
      List.of("OPEN", "PROCESSING", "CLOSED", "REVERSED");
  public static final List<String> RUN_TYPES = List.of("REGULAR", "SUPPLEMENTAL", "BONUS", "FINAL");
  public static final List<String> RUN_STATUSES =
      List.of("PENDING", "CALCULATING", "CALCULATED", "APPROVED", "PAID", "REVERSED", "ERROR");

  public static final String PERIOD_SORT_PATTERN =
      "^(periodStartDate|periodEndDate|payDate|periodName),(asc|desc)$";
  public static final int REASON_MAX = 4000;

  private PayrollConstants() {}
}
