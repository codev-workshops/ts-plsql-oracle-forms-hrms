package com.hrms.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Transactional outbox replacing PKG_NOTIFICATION.send_notification (PKG-N01). The row is written in the
 * caller's transaction, so a notification exists exactly when the business change commits; the legacy
 * autonomous transaction (which queued mail for rolled-back changes) and its swallowed errors are not ported.
 */
@Service
public class NotificationService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Queues an EMAIL to the employee's work address (resolved now, like the legacy package). */
    public long queueEmail(long recipientEmpId, String subject, String body, String referenceTable,
            Long referenceId, String actor) {
        return jdbc.sql("""
                INSERT INTO hrms.notification_queue (recipient_emp_id, recipient_email, notification_type, subject,
                    body, status, reference_table, reference_id, created_by, created_date)
                SELECT e.emp_id, e.email, 'EMAIL', :subject, :body, 'PENDING', :refTable, :refId, :actor, :now
                FROM hrms.employees e WHERE e.emp_id = :empId
                RETURNING notification_id
                """).param("subject", subject).param("body", body).param("refTable", referenceTable)
                .param("refId", referenceId).param("actor", actor).param("now", now()).param("empId", recipientEmpId)
                .query(Long.class).single();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
