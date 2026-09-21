package com.acme.hrms.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Level 1: error_log row survives the rollback of the surrounding transaction. */
class ErrorLogServiceTest {

  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager txm;

  @BeforeAll
  static void setUp() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
    txm = new DataSourceTransactionManager(HrmsPostgres.dataSource());
  }

  @Test
  void errorRowIsWrittenInItsOwnTransactionWithIntegerLegacyCode() {
    ErrorLogService service = proxied(new ErrorLogService(jdbc));
    TransactionStatus outer = txm.getTransaction(new DefaultTransactionDefinition());
    service.record(
        "abc123", ErrorCode.INVALID_CREDENTIALS, 401, "Invalid", null, "/api/auth/login", null);
    txm.rollback(outer);

    Map<String, Object> row =
        jdbc.queryForMap(
            "select error_code, error_key, http_status from error_log where trace_id = 'abc123'");
    assertThat(row.get("error_code")).isEqualTo(-20301);
    assertThat(row.get("error_key")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(row.get("http_status")).isEqualTo(401);
  }

  @Test
  void auditLoginRowAllowedByCheckConstraint() {
    TransactionTemplate tt = new TransactionTemplate(txm);
    AuditService audit = new AuditService(jdbc, Clock.systemUTC());
    long id =
        tt.execute(
            s ->
                audit.log(
                    "USER_SESSIONS",
                    1,
                    AuditService.Action.LOGIN,
                    null,
                    "{}",
                    "james",
                    "127.0.0.1",
                    "1"));
    assertThat(
            jdbc.queryForObject(
                "select action_type from audit_log where audit_id = ?", String.class, id))
        .isEqualTo("LOGIN");
  }

  /** Minimal REQUIRES_NEW wrapper without a Spring context. */
  private static ErrorLogService proxied(ErrorLogService target) {
    TransactionTemplate requiresNew = new TransactionTemplate(txm);
    requiresNew.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new ErrorLogService(jdbc) {
      @Override
      public void record(
          String traceId,
          ErrorCode code,
          int httpStatus,
          String message,
          String detail,
          String requestPath,
          String username) {
        requiresNew.executeWithoutResult(
            s -> target.record(traceId, code, httpStatus, message, detail, requestPath, username));
      }
    };
  }
}
