package com.hrms.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * One-time password reset links (OQ-15). {@code linkBase} is the React reset page; the token is appended
 * after it (the default puts it in the URL fragment, so it never reaches a server log).
 */
@ConfigurationProperties("hrms.auth.password-reset")
public record PasswordResetProperties(Duration tokenTtl, String linkBase) {

    public PasswordResetProperties {
        if (tokenTtl == null) {
            tokenTtl = Duration.ofDays(7);
        }
        if (linkBase == null || linkBase.isBlank()) {
            linkBase = "http://localhost:5173/reset-password#token=";
        }
    }
}
