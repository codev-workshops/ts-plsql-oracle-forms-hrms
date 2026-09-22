package com.acme.hrms.validation.constraints;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.LocalDate;

public class HireDateWithinLimitValidator
    implements ConstraintValidator<HireDateWithinLimit, LocalDate> {

  private final Clock clock;
  private int maxFutureDays;

  public HireDateWithinLimitValidator() {
    this(Clock.systemDefaultZone());
  }

  public HireDateWithinLimitValidator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void initialize(HireDateWithinLimit constraint) {
    this.maxFutureDays = constraint.maxFutureDays();
  }

  @Override
  public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
    return value == null || !value.isAfter(LocalDate.now(clock).plusDays(maxFutureDays));
  }
}
