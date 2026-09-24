package com.acme.hrms.validation.constraints;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class SsnValidator implements ConstraintValidator<Ssn, String> {

  private static final Pattern WIRE = Pattern.compile(Ssn.PATTERN);

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isEmpty()) {
      return true;
    }
    if (!WIRE.matcher(value).matches()) {
      return false;
    }
    String digits = value.replace("-", "");
    return !digits.startsWith("000")
        && !digits.substring(3, 5).equals("00")
        && !digits.substring(5).equals("0000");
  }
}
