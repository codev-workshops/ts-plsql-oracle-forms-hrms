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
  CYCLE_STATUS_INVALID(
      "-20401", HttpStatus.UNPROCESSABLE_ENTITY, "Review cycle not in correct status"),
  REVIEW_STATUS_INVALID(
      "-20402", HttpStatus.UNPROCESSABLE_ENTITY, "Review not found or not in correct status"),
  RATING_OUT_OF_RANGE("-20403", HttpStatus.BAD_REQUEST, "Rating must be between 1.0 and 5.0"),
  CYCLE_NOT_FOUND("CYCLE_NOT_FOUND", HttpStatus.NOT_FOUND, "Review cycle not found"),
  REVIEW_NOT_FOUND("REVIEW_NOT_FOUND", HttpStatus.NOT_FOUND, "Performance review not found"),
  GOAL_NOT_FOUND("GOAL_NOT_FOUND", HttpStatus.NOT_FOUND, "Performance goal not found"),

  // ---- framework
  VALIDATION_FAILED("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, "Request validation failed"),
  PASSWORD_REUSED(
      "PASSWORD_REUSED", HttpStatus.BAD_REQUEST, "New password must differ from the current one"),
  TOKEN_INVALID("TOKEN_INVALID", HttpStatus.UNAUTHORIZED, "Session has expired"),
  FORBIDDEN("FORBIDDEN", HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
  SSO_MODULE_NOT_LEGACY(
      "SSO_MODULE_NOT_LEGACY", HttpStatus.FORBIDDEN, "Module is not served by Oracle Forms"),
  RATE_LIMITED("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts"),
  SSO_LEGACY_UNAVAILABLE(
      "SSO_LEGACY_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "Legacy system is unavailable"),
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
