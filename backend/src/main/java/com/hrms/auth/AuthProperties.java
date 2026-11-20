package com.hrms.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session and lockout policy. Defaults are the accepted OQ-07 (5 failures, 15-minute lock) and
 * OQ-08 (30 minutes absolute from login, legacy {@code c_session_timeout_min}) values.
 */
@ConfigurationProperties("hrms.auth")
public record AuthProperties(Duration sessionTimeout, int maxFailedAttempts, Duration lockDuration) {

    public AuthProperties {
        if (sessionTimeout == null) {
            sessionTimeout = Duration.ofMinutes(30);
        }
        if (maxFailedAttempts <= 0) {
            maxFailedAttempts = 5;
        }
        if (lockDuration == null) {
            lockDuration = Duration.ofMinutes(15);
        }
    }
}
