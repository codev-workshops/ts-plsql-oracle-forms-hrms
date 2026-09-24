package com.acme.hrms.common.error;

import org.springframework.lang.Nullable;

/** {@code -20301}: one indistinguishable failure for every login / current-password cause. */
public class InvalidCredentialsException extends HrmsException {
  public InvalidCredentialsException() {
    super(ErrorCode.INVALID_CREDENTIALS);
  }

  public InvalidCredentialsException(@Nullable String field) {
    super(ErrorCode.INVALID_CREDENTIALS, field);
  }
}
