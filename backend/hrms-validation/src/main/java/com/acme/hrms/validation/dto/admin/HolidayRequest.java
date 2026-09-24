package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Body of POST/PUT /api/admin/holidays (HOLIDAYS; active duplicate of (holidayDate,
 * coalesce(locationCode,'*')) -> -20601, inactive/unknown location -> -20604). The date window is
 * {@code [1990-01-01, today + MAX_FUTURE_DAYS]}.
 */
public class HolidayRequest {

  public static final LocalDate MIN_DATE = LocalDate.of(1990, 1, 1);
  public static final int MAX_FUTURE_DAYS = 3650;

  @NotNull
  @FieldMeta(
      requiredMessage = "Holiday date is required",
      ruleId = "holiday.dateWindow",
      ruleValue = "" + MAX_FUTURE_DAYS,
      ruleErrorCode = "VALIDATION_FAILED",
      ruleMessage = "Holiday date must be between 1990-01-01 and ten years from today")
  private LocalDate holidayDate;

  @NotBlank
  @Size(min = 1, max = 100)
  @FieldMeta(trim = true, requiredMessage = "Holiday name is required")
  private String holidayName;

  @Size(max = 10)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(trim = true, patternMessage = AdminRules.CODE_MESSAGE)
  private String locationCode;

  @FieldMeta private Boolean floatingFlag;

  @FieldMeta private Boolean activeFlag;

  @AssertTrue(message = "holidayDate must be between 1990-01-01 and ten years from today")
  public boolean isHolidayDateWithinWindow() {
    return holidayDate == null
        || (!holidayDate.isBefore(MIN_DATE)
            && !holidayDate.isAfter(LocalDate.now().plusDays(MAX_FUTURE_DAYS)));
  }

  public LocalDate getHolidayDate() {
    return holidayDate;
  }

  public void setHolidayDate(LocalDate holidayDate) {
    this.holidayDate = holidayDate;
  }

  public String getHolidayName() {
    return holidayName;
  }

  public void setHolidayName(String holidayName) {
    this.holidayName = holidayName == null || holidayName.isBlank() ? null : holidayName.trim();
  }

  public String getLocationCode() {
    return locationCode;
  }

  public void setLocationCode(String locationCode) {
    this.locationCode = locationCode == null || locationCode.isBlank() ? null : locationCode.trim();
  }

  public Boolean getFloatingFlag() {
    return floatingFlag;
  }

  public void setFloatingFlag(Boolean floatingFlag) {
    this.floatingFlag = floatingFlag;
  }

  public Boolean getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Boolean activeFlag) {
    this.activeFlag = activeFlag;
  }
}
