package com.acme.hrms.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * US SSN as entered by the user: 9 digits, optionally {@code NNN-NN-NNNN}; no group may be all
 * zeros (HRMS_VALIDATION_LIB.pll {@code validate_ssn}; {@code PKG_COMMON.is_valid_ssn} only checked
 * the digit count). The value is encrypted at the service boundary and is never echoed back by any
 * response (COMPONENT_MAPPING.md §8); responses carry {@code ssnLast4} only.
 */
@Documented
@Constraint(validatedBy = SsnValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Ssn {

  /** Wire pattern exported to the client (digits with optional dashes). */
  String PATTERN = "^[0-9]{3}-?[0-9]{2}-?[0-9]{4}$";

  String message() default "SSN must be 9 digits (NNN-NN-NNNN)";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
