package com.acme.hrms.common.error;

/** {@code -20310} / {@code -20311} / {@code -20312}, always {@code field = "newPassword"}. */
public class PasswordPolicyException extends HrmsException {
  public static final String FIELD = "newPassword";

  public PasswordPolicyException(ErrorCode code, String message) {
    super(code, message, FIELD);
    if (code != ErrorCode.PASSWORD_TOO_SHORT
        && code != ErrorCode.PASSWORD_NO_UPPERCASE
        && code != ErrorCode.PASSWORD_NO_DIGIT) {
      throw new IllegalArgumentException("Not a password policy code: " + code);
    }
  }
}
