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
 * Base of every {@code contracts/p3-employee} and {@code contracts/p4-payroll} request body ({@code
 * additionalProperties: false}). Unknown properties are captured during deserialization (whatever
 * their value, {@code null} included) and rejected by {@link #requireNoUnknownProperties()} at the
 * module's body-validation step, so authority, module flag and required headers keep their frozen
 * precedence (error-codes.md §3).
 */
public abstract class StrictRequest {

  @JsonIgnore private final List<String> unknownProperties = new ArrayList<>();

  @JsonAnySetter
  void unknownProperty(String name, @Nullable Object value) {
    unknownProperties.add(name);
  }

  @JsonIgnore private final List<String> malformedProperties = new ArrayList<>();

  void malformedProperty(String name) {
    malformedProperties.add(name);
  }

  @JsonIgnore
  public List<String> unknownProperties() {
    return List.copyOf(unknownProperties);
  }

  @JsonIgnore
  public List<String> malformedProperties() {
    return List.copyOf(malformedProperties);
  }

  /** {@code 400 VALIDATION_FAILED} on the first property whose wire format broke the contract. */
  public void requireNoMalformedProperties() {
    if (malformedProperties.isEmpty()) {
      return;
    }
    List<ApiError.Detail> details =
        malformedProperties.stream()
            .map(name -> new ApiError.Detail(name, "InvalidFormat", MoneyDeserializer.MESSAGE))
            .toList();
    throw new HrmsException(
        ErrorCode.VALIDATION_FAILED,
        "Request validation failed",
        malformedProperties.get(0),
        details,
        null);
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
