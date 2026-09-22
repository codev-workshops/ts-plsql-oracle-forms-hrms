package com.acme.hrms.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * VAL-01: a hire date may be at most {@link #maxFutureDays()} days after today (single limit,
 * {@code SYSTEM_PARAMETERS HR.MAX_FUTURE_HIRE_DAYS}; legacy had 90 in the form and 180 in {@code
 * TRG_EMP_BEFORE_INSERT}). Violations surface as {@code ApiError.code = -20501}.
 */
@Documented
@Constraint(validatedBy = HireDateWithinLimitValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HireDateWithinLimit {

  String PARAMETER = "HR.MAX_FUTURE_HIRE_DAYS";
  int DEFAULT_MAX_FUTURE_DAYS = 90;

  String message() default "Hire date cannot be more than {maxFutureDays} days in the future";

  int maxFutureDays() default DEFAULT_MAX_FUTURE_DAYS;

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
