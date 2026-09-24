package com.acme.hrms.validation.dto.integration;

import com.acme.hrms.validation.meta.FieldMeta;
import java.time.LocalDate;

/**
 * Body of POST /api/integration/benefits-feed (PKG_INTEGRATION.export_benefits_feed
 * p_effective_date DEFAULT SYSDATE).
 */
public class BenefitsFeedRequest {

  @FieldMeta private LocalDate effectiveDate;

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }
}
