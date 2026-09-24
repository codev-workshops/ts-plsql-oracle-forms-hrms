package com.acme.hrms.payroll;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.employee.StrictRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Runs the hrms-validation constraints on bodies/queries (rendered by GlobalExceptionHandler).
 * Unknown body properties ({@code additionalProperties: false}) are rejected before Bean
 * Validation, after authority and the missing-body check.
 */
@Component
public class PayrollValidation {

  private final Validator validator;

  public PayrollValidation(Validator validator) {
    this.validator = validator;
  }

  public <T> T validate(@Nullable T body) {
    if (body == null) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "Malformed request body", "body");
    }
    if (body instanceof StrictRequest strict) {
      strict.requireNoUnknownProperties();
    }
    Set<ConstraintViolation<T>> violations = validator.validate(body);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    return body;
  }
}
