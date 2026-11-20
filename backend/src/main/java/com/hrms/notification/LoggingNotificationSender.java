package com.hrms.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default sender until an SMTP adapter is configured: logs the notification id only (no addresses or bodies). */
@Configuration
class LoggingNotificationSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Bean
    @ConditionalOnMissingBean(NotificationSender.class)
    NotificationSender loggingSender() {
        return n -> LOG.info("notification {} delivered by the logging sender", n.notificationId());
    }
}
