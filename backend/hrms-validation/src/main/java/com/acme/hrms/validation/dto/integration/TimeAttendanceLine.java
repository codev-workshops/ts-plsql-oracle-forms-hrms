package com.acme.hrms.validation.dto.integration;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed line of the time-attendance CSV (BUG-08: grammar frozen from the legacy comment
 * emp_number,date,hours_regular,hours_overtime; persistence unspecified).
 */
public class TimeAttendanceLine {

  @NotBlank
  @Size(min = 1, max = 20)
  @FieldMeta(trim = true, requiredMessage = "emp_number is required")
  private String empNumber;

  @NotNull
  @FieldMeta(requiredMessage = "date is required")
  private LocalDate workDate;

  @NotNull
  @DecimalMin(value = "0.00")
  @DecimalMax(value = "24.00")
  @Digits(integer = 2, fraction = 2)
  @FieldMeta(requiredMessage = "hours_regular is required")
  private BigDecimal hoursRegular;

  @NotNull
  @DecimalMin(value = "0.00")
  @DecimalMax(value = "24.00")
  @Digits(integer = 2, fraction = 2)
  @FieldMeta(requiredMessage = "hours_overtime is required")
  private BigDecimal hoursOvertime;

  @AssertTrue(message = "hours_regular + hours_overtime must not exceed 24")
  public boolean isDailyTotalValid() {
    return hoursRegular == null
        || hoursOvertime == null
        || hoursRegular.add(hoursOvertime).compareTo(IntegrationRules.HOURS_PER_DAY) <= 0;
  }

  public String getEmpNumber() {
    return empNumber;
  }

  public void setEmpNumber(String empNumber) {
    this.empNumber = empNumber == null || empNumber.isBlank() ? null : empNumber.trim();
  }

  public LocalDate getWorkDate() {
    return workDate;
  }

  public void setWorkDate(LocalDate workDate) {
    this.workDate = workDate;
  }

  public BigDecimal getHoursRegular() {
    return hoursRegular;
  }

  public void setHoursRegular(BigDecimal hoursRegular) {
    this.hoursRegular = hoursRegular;
  }

  public BigDecimal getHoursOvertime() {
    return hoursOvertime;
  }

  public void setHoursOvertime(BigDecimal hoursOvertime) {
    this.hoursOvertime = hoursOvertime;
  }
}
