package com.acme.hrms.validation.dto.leave;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Size;

/** Body of POST /api/leave/requests/{id}/cancel; null reason -> "Cancelled by employee". */
public class LeaveCancelRequest {

  @Size(max = LeaveText.MAX)
  @FieldMeta(trim = true)
  private String reason;

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = LeaveText.blankToNull(reason);
  }
}
