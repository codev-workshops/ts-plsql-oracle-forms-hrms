package com.acme.hrms.auth.employee;

import com.acme.hrms.auth.crypto.FieldEncryptionService;
import com.acme.hrms.employee.SensitiveFieldCipher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** employee-service's {@link SensitiveFieldCipher} backed by the SEC-07 field encryption key. */
@Component
public class SsnFieldCipher implements SensitiveFieldCipher {

  private final FieldEncryptionService encryption;

  public SsnFieldCipher(FieldEncryptionService encryption) {
    this.encryption = encryption;
  }

  @Override
  @Nullable
  public String encrypt(@Nullable String plaintext) {
    return encryption.encrypt(plaintext);
  }

  @Override
  @Nullable
  public String decrypt(@Nullable String stored) {
    return encryption.decrypt(stored);
  }
}
