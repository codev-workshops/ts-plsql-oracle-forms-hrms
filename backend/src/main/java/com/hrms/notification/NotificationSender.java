package com.hrms.notification;

/** Delivery port for queued notifications. The SMTP adapter and its configuration are a deployment concern (P8). */
public interface NotificationSender {

    void send(OutboundNotification notification) throws Exception;

    record OutboundNotification(long notificationId, String recipientEmail, String subject, String body) {
    }
}
