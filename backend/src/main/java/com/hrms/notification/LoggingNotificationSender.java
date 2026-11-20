package com.hrms.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Chooses the outbox sender from {@code hrms.notifications.sender}: {@code log} (default; logs the
 * notification id only, no addresses or bodies) or {@code smtp} ({@link SmtpNotificationSender}).
 */
@Configuration
class LoggingNotificationSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Bean
    @ConditionalOnProperty(name = "hrms.notifications.sender", havingValue = "log", matchIfMissing = true)
    @ConditionalOnMissingBean(NotificationSender.class)
    NotificationSender loggingSender() {
        return n -> LOG.info("notification {} delivered by the logging sender", n.notificationId());
    }

    @Bean
    @ConditionalOnProperty(name = "hrms.notifications.sender", havingValue = "smtp")
    NotificationSender smtpSender(JavaMailSender mail, @Value("${hrms.notifications.from:}") String from) {
        return new SmtpNotificationSender(mail, from);
    }
}
