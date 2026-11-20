package com.hrms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.notification.NotificationSender.OutboundNotification;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** P8 cutover: the outbox sender is chosen by configuration (log by default, SMTP for go-live). */
class NotificationSenderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoggingNotificationSender.class)
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class));

    @Test
    void logSenderIsTheDefault() {
        runner.run(ctx -> assertThat(ctx.getBean(NotificationSender.class))
                .isNotInstanceOf(SmtpNotificationSender.class));
    }

    @Test
    void smtpSenderNeedsMailSettingsAndAFromAddress() {
        runner.withPropertyValues("hrms.notifications.sender=smtp", "spring.mail.host=smtp.example.com",
                "hrms.notifications.from=hrms@example.com")
                .run(ctx -> assertThat(ctx.getBean(NotificationSender.class)).isInstanceOf(SmtpNotificationSender.class));
        runner.withPropertyValues("hrms.notifications.sender=smtp", "spring.mail.host=smtp.example.com")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("hrms.notifications.sender=smtp", "hrms.notifications.from=hrms@example.com")
                .run(ctx -> assertThat(ctx).hasFailed()); // no spring.mail.host: fail fast, never silently log
    }

    @Test
    void smtpSenderMapsTheOutboxRowAndPropagatesFailures() throws Exception {
        List<SimpleMailMessage> sent = new ArrayList<>();
        JavaMailSenderImpl mail = new JavaMailSenderImpl() {
            @Override
            public void send(SimpleMailMessage... messages) {
                if (messages[0].getSubject().contains("FAIL")) {
                    throw new org.springframework.mail.MailSendException("smtp down");
                }
                sent.addAll(List.of(messages));
            }
        };
        SmtpNotificationSender sender = new SmtpNotificationSender(mail, "hrms@example.com");
        sender.send(new OutboundNotification(7, "a@company.com", "Leave Request Approved", "Body"));
        assertThat(sent).singleElement().satisfies(m -> {
            assertThat(m.getFrom()).isEqualTo("hrms@example.com");
            assertThat(m.getTo()).containsExactly("a@company.com");
            assertThat(m.getSubject()).isEqualTo("Leave Request Approved");
            assertThat(m.getText()).isEqualTo("Body");
        });
        assertThatThrownBy(() -> sender.send(new OutboundNotification(8, "a@company.com", "FAIL", "x")))
                .hasMessageContaining("smtp down");
        assertThatThrownBy(() -> new SmtpNotificationSender(mail, " ")).isInstanceOf(IllegalStateException.class);
    }
}
