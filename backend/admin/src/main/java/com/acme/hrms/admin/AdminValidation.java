package com.acme.hrms.admin;

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
 * Body validation for the {@link StrictRequest} admin DTOs: after authority (PreAuthorize) and the
 * missing-body check, wrong wire types (decimal strings sent as numbers) and unknown properties are
 * rejected as 400 before Bean Validation runs.
 */
@Component
public class AdminValidation {

  private final Validator validator;

  public AdminValidation(Validator validator) {
    this.validator = validator;
  }

  public <T extends StrictRequest> T validate(@Nullable T body) {
    if (body == null) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "Malformed request body", "body");
    }
    body.requireNoMalformedProperties();
    body.requireNoUnknownProperties();
    Set<ConstraintViolation<T>> violations = validator.validate(body);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    return body;
  }
}
