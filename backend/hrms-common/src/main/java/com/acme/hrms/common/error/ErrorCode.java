package com.acme.hrms.common.error;

import org.springframework.http.HttpStatus;

/**
 * Wire codes of contracts/p0-foundation/error-codes.md. Legacy codes keep the Oracle
 * RAISE_APPLICATION_ERROR number as a string ("-20301"); framework codes are UPPER_SNAKE_CASE.
 */
public enum ErrorCode {
  // ---- legacy -20xxx (COMPONENT_MAPPING.md §11)
  INVALID_CREDENTIALS("-20301", HttpStatus.UNAUTHORIZED, "Invalid username or password"),
  PASSWORD_TOO_SHORT("-20310", HttpStatus.BAD_REQUEST, "Password must be at least %d characters"),
  PASSWORD_NO_UPPERCASE(
      "-20311", HttpStatus.BAD_REQUEST, "Password must contain an uppercase letter"),
  PASSWORD_NO_DIGIT("-20312", HttpStatus.BAD_REQUEST, "Password must contain a number"),
  EMPLOYEE_NOT_FOUND("-20001", HttpStatus.NOT_FOUND, "Employee not found or not active"),
  DUPLICATE_EMPLOYEE_NUMBER(
      "-20002", HttpStatus.CONFLICT, "Duplicate employee number generated. Please retry."),
  INVALID_DEPARTMENT("-20003", HttpStatus.BAD_REQUEST, "Invalid or inactive department: %s"),
  INVALID_MANAGER("-20004", HttpStatus.BAD_REQUEST, "Invalid or inactive manager: %s"),
  EMPLOYEE_ALREADY_TERMINATED(
      "-20005", HttpStatus.UNPROCESSABLE_ENTITY, "Employee %s is already terminated"),
  NAMES_REQUIRED("-20010", HttpStatus.BAD_REQUEST, "First name and last name are required"),
  INVALID_JOB("-20011", HttpStatus.BAD_REQUEST, "Invalid or inactive job: %s"),
  EMPLOYEE_NOT_ACTIVE(
      "-20012", HttpStatus.UNPROCESSABLE_ENTITY, "Cannot transfer non-active employee. Status: %s"),
  SALARY_NOT_POSITIVE("-20101", HttpStatus.BAD_REQUEST, "Salary must be positive: %s"),
  NO_ACTIVE_SALARY("-20104", HttpStatus.BAD_REQUEST, "No active salary record for employee %s"),
  PERIOD_CLOSED("-20102", HttpStatus.UNPROCESSABLE_ENTITY, "Period already closed: %s"),
  RUN_NOT_APPROVABLE("-20103", HttpStatus.UNPROCESSABLE_ENTITY, "Cannot approve run in status: %s"),
  HIRE_DATE_TOO_FAR(
      "-20501", HttpStatus.BAD_REQUEST, "Hire date cannot be more than 90 days in the future"),
  EMAIL_IN_USE("-20502", HttpStatus.CONFLICT, "Email address already in use: %s"),
  TERMINATED_REACTIVATION(
      "-20503",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "Cannot directly reactivate a terminated employee. Use the rehire process."),
  DIRECT_DELETION_NOT_ALLOWED(
      "-20504",
      HttpStatus.METHOD_NOT_ALLOWED,
      "Direct deletion not allowed. Use termination process or set ACTIVE_FLAG to N."),
  CYCLE_STATUS_INVALID(
      "-20401", HttpStatus.UNPROCESSABLE_ENTITY, "Review cycle not in correct status"),
  REVIEW_STATUS_INVALID(
      "-20402", HttpStatus.UNPROCESSABLE_ENTITY, "Review not found or not in correct status"),
  RATING_OUT_OF_RANGE("-20403", HttpStatus.BAD_REQUEST, "Rating must be between 1.0 and 5.0"),
  CYCLE_NOT_FOUND("CYCLE_NOT_FOUND", HttpStatus.NOT_FOUND, "Review cycle not found"),
  REVIEW_NOT_FOUND("REVIEW_NOT_FOUND", HttpStatus.NOT_FOUND, "Performance review not found"),
  GOAL_NOT_FOUND("GOAL_NOT_FOUND", HttpStatus.NOT_FOUND, "Performance goal not found"),
  LEAVE_INSUFFICIENT_BALANCE(
      "-20201", HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient leave balance"),
  LEAVE_OVERLAP("-20202", HttpStatus.CONFLICT, "Leave request overlaps with existing request"),
  LEAVE_TYPE_INVALID("-20203", HttpStatus.UNPROCESSABLE_ENTITY, "Invalid leave type"),
  LEAVE_STATUS_INVALID(
      "-20204", HttpStatus.UNPROCESSABLE_ENTITY, "Operation not allowed in current status"),
  LEAVE_DATE_ORDER(
      "-20210", HttpStatus.BAD_REQUEST, "Start date must be before or equal to end date"),
  LEAVE_TOO_FAR_IN_PAST(
      "-20211",
      HttpStatus.BAD_REQUEST,
      "Cannot submit leave requests more than 5 days in the past"),
  LEAVE_NO_BUSINESS_DAYS(
      "-20212",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "Leave request must include at least one business day"),
  LEAVE_REQUEST_NOT_FOUND(
      "LEAVE_REQUEST_NOT_FOUND", HttpStatus.NOT_FOUND, "Leave request not found"),

  // ---- framework
  VALIDATION_FAILED("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, "Request validation failed"),
  PASSWORD_REUSED(
      "PASSWORD_REUSED", HttpStatus.BAD_REQUEST, "New password must differ from the current one"),
  TOKEN_INVALID("TOKEN_INVALID", HttpStatus.UNAUTHORIZED, "Session has expired"),
  FORBIDDEN("FORBIDDEN", HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
  DEPENDENT_NOT_FOUND("DEPENDENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Dependent not found"),
  CONTACT_NOT_FOUND("CONTACT_NOT_FOUND", HttpStatus.NOT_FOUND, "Emergency contact not found"),
  CONFLICT("CONFLICT", HttpStatus.CONFLICT, "Record was changed by another user"),
  PRECONDITION_REQUIRED(
      "PRECONDITION_REQUIRED", HttpStatus.PRECONDITION_REQUIRED, "If-Match header is required"),
  MODULE_READ_ONLY(
      "MODULE_READ_ONLY", HttpStatus.CONFLICT, "Employee module is read-only during cutover"),
  SSO_MODULE_NOT_LEGACY(
      "SSO_MODULE_NOT_LEGACY", HttpStatus.FORBIDDEN, "Module is not served by Oracle Forms"),
  RATE_LIMITED("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts"),
  SSO_LEGACY_UNAVAILABLE(
      "SSO_LEGACY_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "Legacy system is unavailable"),
  PERIOD_NOT_FOUND("PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND, "Pay period not found"),
  RUN_NOT_FOUND("RUN_NOT_FOUND", HttpStatus.NOT_FOUND, "Payroll run not found"),
  PAYSLIP_NOT_FOUND("PAYSLIP_NOT_FOUND", HttpStatus.NOT_FOUND, "Payslip not found"),
  SHADOW_REPORT_NOT_FOUND(
      "SHADOW_REPORT_NOT_FOUND",
      HttpStatus.NOT_FOUND,
      "Run has not been calculated by the Java engine"),
  RUN_ALREADY_CALCULATING(
      "RUN_ALREADY_CALCULATING", HttpStatus.CONFLICT, "Payroll run is already being calculated"),
  RUN_NOT_CALCULABLE(
      "RUN_NOT_CALCULABLE",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "Payroll run cannot be calculated in status: %s"),
  RUN_NOT_REVERSIBLE(
      "RUN_NOT_REVERSIBLE",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "Payroll run cannot be reversed in status: %s"),
  MISSING_TAX_RATE(
      "MISSING_TAX_RATE",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "No tax rate for tax year %s and state %s"),
  // ---- P5 reporting / admin / integration (contracts/p5-reporting-decommission/error-codes.md)
  REFERENCE_CODE_CONFLICT("-20601", HttpStatus.CONFLICT, "Reference code already exists: %s"),
  REFERENCE_IN_USE("-20602", HttpStatus.UNPROCESSABLE_ENTITY, "%s %s has %d active %s"),
  REFERENCE_VALUE_RULE("-20603", HttpStatus.BAD_REQUEST, "%s"),
  INVALID_GRADE_OR_LOCATION("-20604", HttpStatus.BAD_REQUEST, "Invalid or inactive %s: %s"),
  DEPARTMENT_CYCLE(
      "-20605", HttpStatus.BAD_REQUEST, "Parent department chain would create a cycle: %s"),
  PARAMETER_NOT_EDITABLE(
      "-20606", HttpStatus.UNPROCESSABLE_ENTITY, "System parameter %s is not editable"),
  PAY_ELEMENT_PROTECTED(
      "-20607", HttpStatus.UNPROCESSABLE_ENTITY, "Pay element %d (%s) is reserved: %s"),
  TAX_BRACKET_OVERLAP(
      "-20608", HttpStatus.CONFLICT, "Bracket [%s, %s) overlaps bracket %d [%s, %s)"),
  TAX_YEAR_LOCKED(
      "-20609", HttpStatus.UNPROCESSABLE_ENTITY, "Tax year %d is locked by payroll run %d (%s)"),
  ROLE_CODE_CONFLICT("-20801", HttpStatus.CONFLICT, "Role code already exists: %s"),
  ROLE_SEEDED("-20802", HttpStatus.UNPROCESSABLE_ENTITY, "Role %s is seeded and read-only"),
  ROLE_IN_USE("-20803", HttpStatus.CONFLICT, "Role %s is assigned to %d accounts"),
  SELF_ACCOUNT_MODIFICATION(
      "-20804", HttpStatus.UNPROCESSABLE_ENTITY, "Cannot modify your own account"),
  LAST_ADMIN_EDIT_HOLDER(
      "-20805",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "Cannot remove the last active account holding ADMIN:EDIT"),
  PRIVILEGE_EXCEEDS_CALLER(
      "-20806", HttpStatus.UNPROCESSABLE_ENTITY, "Cannot grant %s: caller does not hold it"),
  UNKNOWN_ROLE("-20807", HttpStatus.UNPROCESSABLE_ENTITY, "Unknown role: %d"),
  ROLE_NOT_FOUND("ROLE_NOT_FOUND", HttpStatus.NOT_FOUND, "Role not found: %d"),
  USER_NOT_FOUND("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "User account not found: %d"),
  RUN_NOT_EXPORTABLE(
      "-20701", HttpStatus.UNPROCESSABLE_ENTITY, "Cannot export GL feed for run in status: %s"),
  JOB_ALREADY_RUNNING("-20702", HttpStatus.CONFLICT, "A %s job for %s is already running"),
  DUPLICATE_ATTENDANCE_LINE(
      "-20703", HttpStatus.UNPROCESSABLE_ENTITY, "Duplicate employee/date in file: %s %s"),
  IMPORT_REJECTED(
      "-20704",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "No valid rows in time-attendance file (%d lines rejected)"),
  NOT_ACCEPTABLE(
      "NOT_ACCEPTABLE", HttpStatus.NOT_ACCEPTABLE, "Accept must be application/json or text/csv"),
  REFERENCE_NOT_FOUND("REFERENCE_NOT_FOUND", HttpStatus.NOT_FOUND, "Reference row not found"),
  JOB_NOT_FOUND("JOB_NOT_FOUND", HttpStatus.NOT_FOUND, "Leave job not found"),
  FILE_NOT_FOUND("FILE_NOT_FOUND", HttpStatus.NOT_FOUND, "Integration file not found"),
  PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds 5 MiB"),
  UNSUPPORTED_MEDIA_TYPE(
      "UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload part must be text/csv"),
  INTERNAL_ERROR(
      "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

  private final String value;
  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(String value, HttpStatus status, String defaultMessage) {
    this.value = value;
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  /** Wire value, e.g. {@code "-20301"} or {@code "VALIDATION_FAILED"}. */
  public String value() {
    return value;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }

  public boolean isLegacy() {
    return value.startsWith("-20");
  }

  /** Integer form for {@code error_log.error_code}; {@code null} for framework codes. */
  public Integer legacyNumber() {
    return isLegacy() ? Integer.valueOf(value) : null;
  }

  public static ErrorCode fromValue(String value) {
    for (ErrorCode c : values()) {
      if (c.value.equals(value)) {
        return c;
      }
    }
    throw new IllegalArgumentException("Unknown error code " + value);
  }
}
