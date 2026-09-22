package com.acme.hrms.validation.dto.employee;

/**
 * Column bounds / wire patterns shared by the Phase 3 employee DTOs
 * (schema/tables/01_core_tables.sql).
 */
public final class EmployeeRules {

  public static final int NAME_MAX = 50;
  public static final int EMAIL_MAX = 100;
  public static final int PHONE_MAX = 30;
  public static final int ADDRESS_MAX = 200;
  public static final int CITY_MAX = 100;
  public static final int POSTAL_MAX = 20;
  public static final int REASON_CODE_MAX = 30;
  public static final int TERMINATION_REASON_MAX = 50;
  public static final int COMMENTS_MAX = 4000;
  public static final int NOTES_MAX = 4000;

  /**
   * The server phone rule ({@code PKG_COMMON.is_valid_phone}: 10 or 11 digits once every non-digit
   * is stripped) expressed as a wire pattern, so the client pre-check and Bean Validation reject
   * exactly the same strings. Not the PLL rule (VAL-03).
   */
  public static final String PHONE_PATTERN = "^(?:\\D*\\d){10,11}\\D*$";

  public static final String PHONE_MESSAGE = "Phone number must contain 10 or 11 digits";

  public static final String COUNTRY_CODE_PATTERN = "^[A-Z]{2,3}$";
  public static final String LOCATION_CODE_PATTERN = "^[A-Z0-9]{1,10}$";

  public static final String[] GENDER = {"M", "F", "O"};
  public static final int MARITAL_STATUS_MAX = 10;
  public static final int NATIONALITY_MAX = 50;
  public static final int CHANGE_REASON_MAX = 50;
  public static final String[] EMPLOYMENT_TYPE = {"FULL_TIME", "PART_TIME", "CONTRACT", "INTERN"};
  public static final String[] EMPLOYMENT_STATUS = {
    "ACTIVE", "ON_LEAVE", "SUSPENDED", "TERMINATED"
  };
  public static final String[] RELATIONSHIP = {
    "SPOUSE", "CHILD", "PARENT", "DOMESTIC_PARTNER", "OTHER"
  };
  public static final String[] PAY_FREQUENCY = {"WEEKLY", "BIWEEKLY", "SEMIMONTHLY", "MONTHLY"};
  public static final String[] SALARY_BASIS = {"ANNUAL", "HOURLY"};

  private EmployeeRules() {}

  static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
