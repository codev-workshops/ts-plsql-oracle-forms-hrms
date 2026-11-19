package com.hrms.platform.notification;

import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.employee.EmployeeSummary;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notification queue (legacy PKG_NOTIFICATION). {@link #queue} writes a PENDING row in the
 * caller's transaction; {@link #dispatchPending} hands rows to the {@link NotificationSender}.
 */
@Service
public class NotificationService {

    private final JdbcTemplate jdbc;
    private final EmployeeLookup employees;
    private final NotificationSender sender;

    public NotificationService(JdbcTemplate jdbc, EmployeeLookup employees, NotificationSender sender) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.sender = sender;
    }

    public void queue(long recipientEmpId, String subject, String body, String referenceTable, Long referenceId,
                      String createdBy) {
        String email = employees.findById(recipientEmpId).map(EmployeeSummary::email).orElse(null);
        jdbc.update("""
                INSERT INTO notification_queue (recipient_emp_id, recipient_email, notification_type, subject, body,
                                                status, reference_table, reference_id, created_by)
                VALUES (?, ?, 'EMAIL', ?, ?, 'PENDING', ?, ?, ?)
                """, recipientEmpId, email, subject, body, referenceTable, referenceId, createdBy);
    }

    @Transactional
    public int dispatchPending() {
        List<QueuedNotification> pending = jdbc.query("""
                SELECT notification_id, recipient_emp_id, recipient_email, notification_type, subject, body
                FROM notification_queue WHERE status = 'PENDING' ORDER BY priority, notification_id
                FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new QueuedNotification(rs.getLong(1), (Long) rs.getObject(2, Long.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
        for (QueuedNotification n : pending) {
            try {
                sender.send(n);
                jdbc.update("UPDATE notification_queue SET status = 'SENT', sent_date = now() WHERE notification_id = ?",
                        n.id());
            } catch (RuntimeException e) {
                jdbc.update("""
                        UPDATE notification_queue SET retry_count = retry_count + 1, error_message = ?,
                               status = CASE WHEN retry_count + 1 >= 3 THEN 'FAILED' ELSE 'PENDING' END
                        WHERE notification_id = ?
                        """, e.getMessage(), n.id());
            }
        }
        return pending.size();
    }
}
