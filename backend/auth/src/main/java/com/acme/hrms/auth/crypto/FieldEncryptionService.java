package com.acme.hrms.auth.crypto;

import com.acme.hrms.auth.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Replaces PKG_SECURITY.encrypt_sensitive / decrypt_sensitive (AES-CBC, hard-coded key, SEC-07)
 * with AES-256-GCM in Java - not pgcrypto (MODERNIZATION_BLUEPRINT.md §9 decision B). Wire format:
 * {@code enc:<keyId>:<base64(iv[12] || ciphertext || tag[16])>}. The key comes from configuration /
 * vault, never from source. Deliberate divergence: legacy ciphertexts are NOT decryptable here -
 * SSN / bank data is re-encrypted by the CDC load (tools/cdc-sync) during Phase 3.
 */
@Service
public class FieldEncryptionService {

  public static final String PREFIX = "enc:";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecureRandom random = new SecureRandom();
  @Nullable private final SecretKey key;
  private final String keyId;

  @Autowired
  public FieldEncryptionService(AuthProperties props) {
    this(props.encryption().keyBase64(), props.encryption().keyId());
  }

  public FieldEncryptionService(@Nullable String keyBase64, String keyId) {
    this.keyId = keyId;
    if (keyBase64 == null || keyBase64.isBlank()) {
      this.key = null;
    } else {
      byte[] raw = Base64.getDecoder().decode(keyBase64);
      if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
        throw new IllegalArgumentException("AES key must be 128/192/256 bits");
      }
      this.key = new SecretKeySpec(raw, "AES");
    }
  }

  public boolean isEnabled() {
    return key != null;
  }

  @Nullable
  public String encrypt(@Nullable String plaintext) {
    if (plaintext == null) {
      return null;
    }
    requireKey();
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
      byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  @Nullable
  public String decrypt(@Nullable String stored) {
    if (stored == null) {
      return null;
    }
    requireKey();
    if (!stored.startsWith(PREFIX)) {
      throw new IllegalArgumentException("not an hrms ciphertext");
    }
    String[] parts = stored.split(":", 3);
    if (parts.length != 3 || !keyId.equals(parts[1])) {
      throw new IllegalArgumentException("unknown key id");
    }
    byte[] blob = Base64.getDecoder().decode(parts[2]);
    if (blob.length < IV_BYTES + TAG_BITS / 8) {
      throw new IllegalArgumentException("ciphertext too short");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
      cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
      byte[] pt = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
      return new String(pt, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("ciphertext authentication failed", e);
    }
  }

  private void requireKey() {
    if (key == null) {
      throw new IllegalStateException("hrms.auth.encryption.key-base64 is not configured");
    }
  }
}
