package com.acme.hrms.common.error;

import org.springframework.lang.Nullable;

/**
 * Receives every error the handler renders so it can be persisted to {@code error_log} in its own
 * transaction (implemented by hrms-audit {@code ErrorLogService}). hrms-common only defines the
 * port so the handler has no dependency on the audit module.
 */
public interface ErrorSink {
  void record(
      String traceId,
      ErrorCode code,
      int httpStatus,
      String message,
      @Nullable String detail,
      @Nullable String requestPath,
      @Nullable String username);
}
