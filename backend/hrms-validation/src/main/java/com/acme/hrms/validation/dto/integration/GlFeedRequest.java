package com.acme.hrms.validation.dto.integration;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/integration/gl-feed (PKG_INTEGRATION.generate_gl_journal p_run_id; run must be
 * APPROVED/PAID -> -20701).
 */
public class GlFeedRequest {

  @NotNull
  @Min(1)
  @FieldMeta(requiredMessage = "Payroll run is required")
  private Integer runId;

  public Integer getRunId() {
    return runId;
  }

  public void setRunId(Integer runId) {
    this.runId = runId;
  }
}
