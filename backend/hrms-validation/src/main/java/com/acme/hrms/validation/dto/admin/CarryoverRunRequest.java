package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/admin/leave/carryover (P2 CarryoverRunRequest, mounted in P5;
 * PKG_LEAVE.process_carryover p_year).
 */
public class CarryoverRunRequest {

  @NotNull
  @Min(2000)
  @Max(2099)
  @FieldMeta(requiredMessage = "Year is required")
  private Integer year;

  public Integer getYear() {
    return year;
  }

  public void setYear(Integer year) {
    this.year = year;
  }
}
