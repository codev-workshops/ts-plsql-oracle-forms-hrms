package com.hrms.notification;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP delivery for the outbox (replaces UTL_MAIL in PKG_NOTIFICATION.process_queue). Enabled with
 * {@code hrms.notifications.sender=smtp} plus the standard {@code spring.mail.*} settings; a send failure
 * propagates, so the dispatcher marks the row FAILED and retries it (retry cap 3).
 */
public class SmtpNotificationSender implements NotificationSender {

    private final JavaMailSender mail;
    private final String from;

    public SmtpNotificationSender(JavaMailSender mail, String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("hrms.notifications.from is required when hrms.notifications.sender=smtp");
        }
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(OutboundNotification n) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(n.recipientEmail());
        message.setSubject(n.subject());
        message.setText(n.body());
        mail.send(message);
    }
}
