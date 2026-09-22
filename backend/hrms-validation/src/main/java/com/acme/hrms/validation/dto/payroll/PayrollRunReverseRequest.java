package com.acme.hrms.validation.dto.payroll;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/payroll/runs/{runId}/reverse; PKG_PAYROLL.reverse_payroll p_reason has no
 * default.
 */
public class PayrollRunReverseRequest {

  @NotBlank
  @Size(min = 1, max = PayrollConstants.REASON_MAX)
  @FieldMeta(trim = true, requiredMessage = "A reversal reason is required")
  private String reason;

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason == null || reason.isBlank() ? null : reason.trim();
  }
}
