package com.acme.hrms.auth.repo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Opaque refresh tokens: 32 random bytes (base64url) handed to the client only in the {@code
 * hrms_refresh} cookie; the table stores SHA-256(token). Rotation revokes the old row and links it
 * to its replacement so a replay of the old token is detected.
 */
@Repository
public class RefreshTokenRepository {

  public record RefreshToken(
      String tokenHash,
      long userId,
      long sessionId,
      String jti,
      Instant expiresAt,
      @Nullable Instant revokedAt) {}

  private final JdbcTemplate jdbc;
  private final SecureRandom random = new SecureRandom();

  public RefreshTokenRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public String newOpaqueToken() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public void store(
      String token, long userId, long sessionId, String jti, Instant issuedAt, Instant expiresAt) {
    jdbc.update(
        "insert into refresh_tokens (token_hash, user_id, session_id, jti, issued_at, expires_at)"
            + " values (?, ?, ?, ?, ?, ?)",
        hash(token),
        userId,
        sessionId,
        jti,
        ts(issuedAt),
        ts(expiresAt));
  }

  public Optional<RefreshToken> find(String token) {
    return jdbc
        .query(
            "select token_hash, user_id, session_id, jti, expires_at, revoked_at from"
                + " refresh_tokens where token_hash = ?",
            (rs, i) ->
                new RefreshToken(
                    rs.getString("token_hash"),
                    rs.getLong("user_id"),
                    rs.getLong("session_id"),
                    rs.getString("jti"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("revoked_at") == null
                        ? null
                        : rs.getTimestamp("revoked_at").toInstant()),
            hash(token))
        .stream()
        .findFirst();
  }

  public void revoke(String tokenHash, Instant now, @Nullable String replacedByHash) {
    jdbc.update(
        "update refresh_tokens set revoked_at = ?, replaced_by = ? where token_hash = ?"
            + " and revoked_at is null",
        ts(now),
        replacedByHash,
        tokenHash);
  }

  public void revokeAllForSession(long sessionId, Instant now) {
    jdbc.update(
        "update refresh_tokens set revoked_at = ? where session_id = ? and revoked_at is null",
        ts(now),
        sessionId);
  }

  static LocalDateTime ts(Instant i) {
    return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
