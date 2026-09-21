package com.acme.hrms.validation.meta;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/** Closed string enumeration (exported as {@code "type": "enum"}). */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AllowedValues.Validator.class)
public @interface AllowedValues {
  String[] value();

  String message() default "must be one of the allowed values";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<AllowedValues, String> {
    private String[] allowed;

    @Override
    public void initialize(AllowedValues a) {
      this.allowed = a.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
      return value == null || Arrays.asList(allowed).contains(value);
    }
  }
}
