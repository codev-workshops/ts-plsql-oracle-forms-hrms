package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.FieldMeta;
import java.time.LocalDate;

/**
 * Body of POST /api/admin/leave/accrual (P2 AccrualRunRequest, mounted in P5; p_accrual_date
 * DEFAULT SYSDATE).
 */
public class AccrualRunRequest {

  @FieldMeta private LocalDate accrualDate;

  public LocalDate getAccrualDate() {
    return accrualDate;
  }

  public void setAccrualDate(LocalDate accrualDate) {
    this.accrualDate = accrualDate;
  }
}
