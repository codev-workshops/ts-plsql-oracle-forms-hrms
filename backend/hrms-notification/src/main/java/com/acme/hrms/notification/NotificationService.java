package com.acme.hrms.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PKG_NOTIFICATION.queue_notification replacement: enqueues into {@code notification_queue}. The
 * legacy UTL_MAIL dispatcher is out of scope for P0; a Java sender drains the queue later.
 */
@Service
public class NotificationService {

  public enum Type {
    EMAIL,
    IN_APP,
    SMS
  }

  private final JdbcTemplate jdbc;

  public NotificationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public long enqueue(
      @Nullable Long recipientEmpId,
      @Nullable String recipientEmail,
      Type type,
      String subject,
      String body,
      int priority,
      @Nullable String referenceTable,
      @Nullable Long referenceId,
      String createdBy) {
    if (recipientEmpId == null && recipientEmail == null) {
      throw new IllegalArgumentException("recipient required");
    }
    if (priority < 1 || priority > 9) {
      throw new IllegalArgumentException("priority 1..9");
    }
    long id = jdbc.queryForObject("select nextval('seq_notification')", Long.class);
    jdbc.update(
        "insert into notification_queue (notification_id, recipient_emp_id, recipient_email,"
            + " notification_type, subject, body, status, priority, reference_table,"
            + " reference_id, created_by) values (?,?,?,?,?,?,'PENDING',?,?,?,?)",
        id,
        recipientEmpId,
        recipientEmail,
        type.name(),
        subject.length() > 200 ? subject.substring(0, 200) : subject,
        body,
        priority,
        referenceTable == null ? null : referenceTable.toUpperCase(),
        referenceId,
        createdBy);
    return id;
  }
}
