package com.hrms.common;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps every error to the D-05 problem+json envelope. Messages never carry SQL or stack traces. */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        ErrorCode c = e.code();
        return problem(Problems.of(c.status(), c.name(), e.legacyCode(), e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        return problem(Problems.of(ErrorCode.FORBIDDEN, "Permission denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException e) {
        return problem(Problems.of(ErrorCode.UNAUTHENTICATED, "Authentication required"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        ProblemDetail p = Problems.of(ErrorCode.VALIDATION_FAILED, "Invalid request parameter");
        return problem(Problems.withErrors(p, List.of(Map.of("field", e.getName(), "message", "invalid value"))));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException e) {
        List<Map<String, String>> errors = e.getConstraintViolations().stream()
                .map(v -> Map.of("field", lastNode(v.getPropertyPath().toString()), "message", v.getMessage()))
                .toList();
        return problem(Problems.withErrors(Problems.of(ErrorCode.VALIDATION_FAILED, "Validation failed"), errors));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> concurrent(RuntimeException e) {
        return problem(Problems.of(ErrorCode.CONCURRENT_MODIFICATION, "The record was modified concurrently"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        ProblemDetail p = Problems.of(ErrorCode.INTERNAL_ERROR, "Unexpected error");
        LOG.error("Unhandled error traceId={}", p.getProperties().get("traceId"), e);
        return problem(p);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        ProblemDetail p = Problems.withErrors(Problems.of(ErrorCode.VALIDATION_FAILED, "Validation failed"), errors);
        return ResponseEntity.status(p.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    /** Framework errors (unreadable body, 404, 405, missing parameter...) keep their status, gain a code. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ErrorCode code = switch (status.value()) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 500 -> ErrorCode.INTERNAL_ERROR;
            default -> ErrorCode.VALIDATION_FAILED;
        };
        String detail = status.is5xxServerError() ? "Unexpected error" : (status.value() == 404 ? "Not found" : "Invalid request");
        ProblemDetail p = Problems.of(status, code.name(), null, detail);
        HttpHeaders h = new HttpHeaders();
        h.putAll(headers);
        h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return ResponseEntity.status(status).headers(h).body(p);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail p) {
        return ResponseEntity.status(p.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    private static String lastNode(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }
}
