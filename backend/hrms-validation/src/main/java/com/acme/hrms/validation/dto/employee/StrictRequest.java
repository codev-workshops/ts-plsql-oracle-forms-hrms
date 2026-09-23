package com.acme.hrms.validation.dto.employee;

import com.acme.hrms.common.error.ApiError;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Base of every {@code contracts/p3-employee} request body ({@code additionalProperties: false}).
 * Unknown properties are captured during deserialization (whatever their value, {@code null}
 * included) and rejected by {@link #requireNoUnknownProperties()} at the module's body-validation
 * step, so authority, module flag and required headers keep their frozen precedence (error-codes.md
 * §3).
 */
public abstract class StrictRequest {

  @JsonIgnore private final List<String> unknownProperties = new ArrayList<>();

  @JsonAnySetter
  void unknownProperty(String name, @Nullable Object value) {
    unknownProperties.add(name);
  }

  @JsonIgnore
  public List<String> unknownProperties() {
    return List.copyOf(unknownProperties);
  }

  /** {@code 400 VALIDATION_FAILED} on the first unknown property, one detail per property. */
  public void requireNoUnknownProperties() {
    if (unknownProperties.isEmpty()) {
      return;
    }
    List<ApiError.Detail> details =
        unknownProperties.stream()
            .map(name -> new ApiError.Detail(name, "UnknownProperty", "Unknown property"))
            .toList();
    throw new HrmsException(
        ErrorCode.VALIDATION_FAILED,
        "Request validation failed",
        unknownProperties.get(0),
        details,
        null);
  }
}
