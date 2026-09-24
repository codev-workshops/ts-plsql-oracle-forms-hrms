package com.acme.hrms.validation.dto.auth;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/admin/users/{userId}/status (USER_ACCOUNTS.STATUS, CHK_UA_STATUS; auth-owned).
 * Self -> -20804; last ADMIN:EDIT -> -20805. {@code reason} goes to the AUDIT_LOG row only.
 */
public class UserStatusRequest {

  @NotNull
  @AllowedValues({"ACTIVE", "DISABLED"})
  @FieldMeta(requiredMessage = "Status is required")
  private String status;

  @Size(max = 200)
  @FieldMeta(trim = true)
  private String reason;

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason == null || reason.isBlank() ? null : reason.trim();
  }
}
