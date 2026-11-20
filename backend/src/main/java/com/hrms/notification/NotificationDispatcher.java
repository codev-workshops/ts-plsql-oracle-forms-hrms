package com.hrms.notification;

import com.hrms.notification.NotificationSender.OutboundNotification;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces PKG_NOTIFICATION.process_queue and retry_failed (PKG-N01): PENDING e-mails with an address are
 * sent in priority, then age, order; a failure marks the row FAILED and increments RETRY_COUNT.
 * {@code FOR UPDATE SKIP LOCKED} lets several instances dispatch without sending a row twice.
 */
@Service
public class NotificationDispatcher {

    private final JdbcClient jdbc;
    private final NotificationSender sender;
    private final Clock clock;

    public NotificationDispatcher(JdbcClient jdbc, NotificationSender sender, Clock clock) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.clock = clock;
    }

    public record DispatchResult(int sent, int failed) {
    }

    @Transactional
    public DispatchResult dispatchPending(int batchSize) {
        List<OutboundNotification> batch = jdbc.sql("""
                SELECT notification_id, recipient_email, subject, body FROM hrms.notification_queue
                WHERE status = 'PENDING' AND notification_type = 'EMAIL' AND recipient_email IS NOT NULL
                ORDER BY priority, created_date, notification_id
                LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("limit", batchSize)
                .query((rs, i) -> new OutboundNotification(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)))
                .list();
        int sent = 0;
        int failed = 0;
        for (OutboundNotification n : batch) {
            try {
                sender.send(n);
                jdbc.sql("UPDATE hrms.notification_queue SET status = 'SENT', sent_date = :now, error_message = NULL "
                        + "WHERE notification_id = :id").param("now", now()).param("id", n.notificationId()).update();
                sent++;
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                jdbc.sql("""
                        UPDATE hrms.notification_queue SET status = 'FAILED', error_message = :msg,
                            retry_count = retry_count + 1
                        WHERE notification_id = :id
                        """).param("msg", msg.length() > 4000 ? msg.substring(0, 4000) : msg)
                        .param("id", n.notificationId()).update();
                failed++;
            }
        }
        return new DispatchResult(sent, failed);
    }

    /** FAILED rows below the retry limit go back to PENDING (legacy default limit: 3). */
    @Transactional
    public int requeueFailed(int maxRetries) {
        return jdbc.sql("""
                UPDATE hrms.notification_queue SET status = 'PENDING', error_message = NULL
                WHERE status = 'FAILED' AND retry_count < :max
                """).param("max", maxRetries).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
