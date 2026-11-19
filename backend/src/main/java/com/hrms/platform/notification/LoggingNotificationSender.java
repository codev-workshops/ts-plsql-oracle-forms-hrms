package com.hrms.platform.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(QueuedNotification n) {
        log.info("notification {} -> emp {}: {}", n.id(), n.recipientEmpId(), n.subject());
    }
}
