package com.acme.hrms.integration;

import com.acme.hrms.common.error.ApiError;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.GlobalExceptionHandler;
import com.acme.hrms.common.error.HrmsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Servlet-level multipart limit → contract {@code 413 PAYLOAD_TOO_LARGE}. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadLimitAdvice {
  private final GlobalExceptionHandler handler;

  public UploadLimitAdvice(GlobalExceptionHandler handler) {
    this.handler = handler;
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiError> tooLarge(
      MaxUploadSizeExceededException e, HttpServletRequest request) {
    return handler.handleHrms(
        new HrmsException(ErrorCode.PAYLOAD_TOO_LARGE, "File exceeds 5 MiB", "file"), request);
  }
}
