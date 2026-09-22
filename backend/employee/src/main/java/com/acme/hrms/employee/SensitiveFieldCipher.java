package com.acme.hrms.employee;

import org.springframework.lang.Nullable;

/**
 * SSN encryption at the service boundary (SEC-07). Implemented by auth-service's {@code
 * FieldEncryptionService}; employee-service never sees key material.
 */
public interface SensitiveFieldCipher {

  @Nullable
  String encrypt(@Nullable String plaintext);

  @Nullable
  String decrypt(@Nullable String stored);
}
