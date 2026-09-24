package com.acme.hrms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class NotificationServiceTest {

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void setUp() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
  }

  @Test
  void enqueuesPendingRow() {
    NotificationService s = new NotificationService(jdbc);
    long id =
        s.enqueue(
            1L,
            null,
            NotificationService.Type.IN_APP,
            "Welcome",
            "Hello",
            5,
            "user_accounts",
            1L,
            "SYSTEM");
    assertThat(
            jdbc.queryForObject(
                "select status from notification_queue where notification_id = ?",
                String.class,
                id))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select reference_table from notification_queue where notification_id = ?",
                String.class,
                id))
        .isEqualTo("USER_ACCOUNTS");
  }

  @Test
  void rejectsMissingRecipient() {
    NotificationService s = new NotificationService(jdbc);
    assertThatThrownBy(
            () ->
                s.enqueue(null, null, NotificationService.Type.EMAIL, "s", "b", 5, null, null, "X"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
