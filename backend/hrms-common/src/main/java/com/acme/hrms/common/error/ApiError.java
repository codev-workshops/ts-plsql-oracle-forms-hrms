package com.acme.hrms.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.lang.Nullable;

/** Error envelope of openapi.yaml {@code ApiError} - built only by GlobalExceptionHandler. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String code,
    String message,
    @Nullable String field,
    String traceId,
    @Nullable List<Detail> details) {

  public record Detail(String field, String code, String message) {}
}
