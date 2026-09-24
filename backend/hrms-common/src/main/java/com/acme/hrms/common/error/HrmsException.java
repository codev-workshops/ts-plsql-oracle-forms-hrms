package com.acme.hrms.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;

/** Base of every business error; translated to {@link ApiError} only by GlobalExceptionHandler. */
public class HrmsException extends RuntimeException {

  private final ErrorCode code;
  @Nullable private final String field;
  @Nullable private final List<ApiError.Detail> details;

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
    this(code, message, field, null, cause);
  }

  public HrmsException(
      ErrorCode code,
      String message,
      @Nullable String field,
      @Nullable List<ApiError.Detail> details,
      @Nullable Throwable cause) {
    super(message, cause);
    this.code = code;
    this.field = field;
    this.details = details == null ? null : List.copyOf(details);
  }

  public ErrorCode code() {
    return code;
  }

  /** HTTP status to render; subclasses may override when a route re-raises a recorded code. */
  public HttpStatus status() {
    return code.status();
  }

  @Nullable
  public String field() {
    return field;
  }

  @Nullable
  public List<ApiError.Detail> details() {
    return details;
  }
}
