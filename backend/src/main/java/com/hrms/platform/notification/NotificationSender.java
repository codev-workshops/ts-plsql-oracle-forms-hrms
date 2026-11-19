package com.hrms.platform.notification;

/** Delivery channel for queued notifications (open question 4: logging/no-op locally). */
public interface NotificationSender {

    void send(QueuedNotification notification);
}
