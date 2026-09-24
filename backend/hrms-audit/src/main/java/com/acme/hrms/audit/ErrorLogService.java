package com.acme.hrms.audit;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.ErrorSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * HRMS_COMMON.pll {@code handle_error} (PRAGMA AUTONOMOUS_TRANSACTION) replacement: every rendered
 * ApiError is persisted under its traceId in a REQUIRES_NEW transaction so it survives the rollback
 * of the failing request.
 */
@Service
public class ErrorLogService implements ErrorSink {

  private final JdbcTemplate jdbc;

  public ErrorLogService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      String traceId,
      ErrorCode code,
      int httpStatus,
      String message,
      @Nullable String detail,
      @Nullable String requestPath,
      @Nullable String username) {
    jdbc.update(
        "insert into error_log (error_id, trace_id, error_code, error_key, http_status, message,"
            + " detail, request_path, username) values (nextval('seq_error_log'),?,?,?,?,?,?,?,?)",
        traceId,
        code.legacyNumber(),
        code.name(),
        httpStatus,
        truncate(message, 4000),
        detail,
        truncate(requestPath, 500),
        truncate(username, 100));
  }

  @Nullable
  private static String truncate(@Nullable String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
