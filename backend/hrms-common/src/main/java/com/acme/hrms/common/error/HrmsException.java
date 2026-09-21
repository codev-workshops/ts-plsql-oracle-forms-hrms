package com.acme.hrms.common.error;

import org.springframework.lang.Nullable;

/** Base of every business error; translated to {@link ApiError} only by GlobalExceptionHandler. */
public class HrmsException extends RuntimeException {

  private final ErrorCode code;
  @Nullable private final String field;

  public HrmsException(ErrorCode code) {
    this(code, code.defaultMessage(), null, null);
  }

  public HrmsException(ErrorCode code, @Nullable String field) {
    this(code, code.defaultMessage(), field, null);
  }

  public HrmsException(ErrorCode code, String message, @Nullable String field) {
    this(code, message, field, null);
  }

  public HrmsException(
      ErrorCode code, String message, @Nullable String field, @Nullable Throwable cause) {
    super(message, cause);
    this.code = code;
    this.field = field;
  }

  public ErrorCode code() {
    return code;
  }

  @Nullable
  public String field() {
    return field;
  }
}
