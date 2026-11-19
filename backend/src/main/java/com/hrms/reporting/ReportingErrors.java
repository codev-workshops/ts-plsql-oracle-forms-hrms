package com.hrms.reporting;

import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorResponse;
import com.hrms.platform.error.GlobalExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ReportingController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReportingErrors {

    private final GlobalExceptionHandler shared;

    ReportingErrors(GlobalExceptionHandler shared) {
        this.shared = shared;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> apiError(ApiException exception) {
        return json(shared.handleApi(exception));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidRequest(Exception exception) {
        return json(shared.handleUnreadable(exception));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> unacceptableMediaType() {
        return ResponseEntity.status(406).build();
    }

    // Errors remain JSON even when the requested report format is CSV.
    private static ResponseEntity<ErrorResponse> json(ResponseEntity<ErrorResponse> response) {
        return ResponseEntity.status(response.getStatusCode())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());
    }
}
