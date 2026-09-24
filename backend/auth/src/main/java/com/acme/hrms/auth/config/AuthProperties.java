package com.acme.hrms.auth.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.lang.Nullable;

/** {@code hrms.auth.*} - see application.yml for the documented defaults. */
@ConfigurationProperties(prefix = "hrms.auth")
public record AuthProperties(
    Jwt jwt, Refresh refresh, RateLimit rateLimit, Encryption encryption, Cookie cookie) {

  /**
   * @param issuer {@code iss} claim
   * @param privateKeyPem PKCS#8 RSA private key (PEM). Empty → an ephemeral key pair is generated
   *     at start-up (dev/test only; tokens do not survive a restart).
   * @param keyId {@code kid} header
   */
  public record Jwt(
      @DefaultValue("hrms") String issuer,
      @Nullable String privateKeyPem,
      @DefaultValue("hrms-p0") String keyId) {}

  public record Refresh(@DefaultValue("8h") Duration ttl) {}

  /** 5 failed attempts per username per 15 minutes (openapi.yaml x-rate-limit). */
  public record RateLimit(
      @DefaultValue("5") int maxFailures, @DefaultValue("15m") Duration window) {}

  /** Base64 128/192/256-bit AES key for FieldEncryptionService. Empty → service disabled. */
  public record Encryption(@Nullable String keyBase64, @DefaultValue("p0-k1") String keyId) {}

  public record Cookie(
      @DefaultValue("hrms_refresh") String name,
      @DefaultValue("/api/auth") String path,
      @DefaultValue("true") boolean secure,
      @DefaultValue("Strict") String sameSite) {}

  public static List<String> publicPaths() {
    return List.of("/api/auth/login", "/api/auth/refresh");
  }
}
