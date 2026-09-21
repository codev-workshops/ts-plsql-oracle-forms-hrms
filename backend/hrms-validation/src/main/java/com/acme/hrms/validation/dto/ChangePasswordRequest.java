package com.acme.hrms.validation.dto;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * newPassword carries only the structural bounds here; the -20310/-20311/-20312 rules are applied
 * by {@code PasswordPolicy} so the legacy code (not VALIDATION_FAILED) reaches the client.
 */
public class ChangePasswordRequest {

  @NotNull
  @Size(min = 1, max = 128)
  @FieldMeta(requiredMessage = "Current password is required")
  private String currentPassword;

  @NotNull
  @Size(max = 128)
  @FieldMeta(requiredMessage = "New password is required")
  private String newPassword;

  public String getCurrentPassword() {
    return currentPassword;
  }

  public void setCurrentPassword(String currentPassword) {
    this.currentPassword = currentPassword;
  }

  public String getNewPassword() {
    return newPassword;
  }

  public void setNewPassword(String newPassword) {
    this.newPassword = newPassword;
  }
}
