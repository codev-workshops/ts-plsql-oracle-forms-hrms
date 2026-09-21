package com.acme.hrms.validation.dto.performance;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Body of POST/PUT /api/performance/cycles (contracts/p1-performance/openapi.yaml). */
public class ReviewCycleRequest {

  @NotBlank
  @Size(min = 1, max = 100)
  @FieldMeta(trim = true, requiredMessage = "Cycle name is required")
  private String cycleName;

  @NotNull
  @Min(2000)
  @Max(2099)
  @FieldMeta(requiredMessage = "Cycle year is required")
  private Integer cycleYear;

  @NotNull
  @FieldMeta(requiredMessage = "Start date is required")
  private LocalDate startDate;

  @NotNull
  @FieldMeta(
      requiredMessage = "End date is required",
      formatMessage = "End date must be on or after the start date")
  private LocalDate endDate;

  @FieldMeta private LocalDate selfReviewDue;

  @FieldMeta private LocalDate managerReviewDue;

  @FieldMeta private LocalDate calibrationDue;

  public String getCycleName() {
    return cycleName;
  }

  public void setCycleName(String cycleName) {
    this.cycleName = cycleName == null ? null : cycleName.trim();
  }

  public Integer getCycleYear() {
    return cycleYear;
  }

  public void setCycleYear(Integer cycleYear) {
    this.cycleYear = cycleYear;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public void setEndDate(LocalDate endDate) {
    this.endDate = endDate;
  }

  public LocalDate getSelfReviewDue() {
    return selfReviewDue;
  }

  public void setSelfReviewDue(LocalDate selfReviewDue) {
    this.selfReviewDue = selfReviewDue;
  }

  public LocalDate getManagerReviewDue() {
    return managerReviewDue;
  }

  public void setManagerReviewDue(LocalDate managerReviewDue) {
    this.managerReviewDue = managerReviewDue;
  }

  public LocalDate getCalibrationDue() {
    return calibrationDue;
  }

  public void setCalibrationDue(LocalDate calibrationDue) {
    this.calibrationDue = calibrationDue;
  }
}
