package com.acme.hrms.validation.dto.admin;

/** Frozen admin / reporting vocabulary (contracts/p5-reporting-decommission/README.md). */
public final class AdminRules {

  /**
   * Natural codes of the reference tables (DEPT_CODE, GRADE_CODE, JOB_CODE, LOCATION_CODE,
   * LEAVE_TYPE_CODE).
   */
  public static final String CODE_PATTERN = "^[A-Z0-9_-]+$";

  public static final String CODE_MESSAGE = "Code must be upper-case letters, digits, '_' or '-'";

  /** SYSTEM_PARAMETERS.PARAM_GROUP / PARAM_CODE. */
  public static final String PARAM_PATTERN = "^[A-Z][A-Z0-9_]*$";

  public static final String PARAM_MESSAGE =
      "Must start with a letter and contain only A-Z, 0-9 or '_'";

  /** Same rule as the P3 employee phone fields. */
  public static final String PHONE_PATTERN = "^(?:\\D*\\d){10,11}\\D*$";

  public static final int PAGE_SIZE_MAX = 100;
  public static final int REPORT_PAGE_SIZE_MAX = 200;
  public static final int AUDIT_RANGE_MAX_DAYS = 366;

  private AdminRules() {}
}
