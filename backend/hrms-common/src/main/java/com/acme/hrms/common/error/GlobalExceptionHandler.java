package com.acme.hrms.common.error;

import com.acme.hrms.common.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single builder of {@link ApiError} (error-codes.md §3). Every rendered error is also handed
 * to the {@link ErrorSink} (REQUIRES_NEW in hrms-audit) with the same trace id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final Optional<ErrorSink> errorSink;

  public GlobalExceptionHandler(Optional<ErrorSink> errorSink) {
    this.errorSink = errorSink;
  }

  @ExceptionHandler(HrmsException.class)
  public ResponseEntity<ApiError> handleHrms(HrmsException e, HttpServletRequest request) {
    ResponseEntity<ApiError> response =
        render(e.code(), e.getMessage(), e.field(), e.details(), request, e, false);
    if (e.status() != e.code().status()) {
      response =
          ResponseEntity.status(e.status()).headers(response.getHeaders()).body(response.getBody());
    }
    if (e instanceof RateLimitedException rl) {
      return ResponseEntity.status(response.getStatusCode())
          .headers(response.getHeaders())
          .header(HttpHeaders.RETRY_AFTER, Long.toString(rl.retryAfterSeconds()))
          .body(response.getBody());
    }
    return response;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleBodyValidation(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    List<ApiError.Detail> details = new ArrayList<>();
    for (ObjectError error : e.getBindingResult().getAllErrors()) {
      String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
      details.add(new ApiError.Detail(field, error.getCode(), error.getDefaultMessage()));
    }
    return validationFailed(details, request, e);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiError> handleConstraintViolation(
      ConstraintViolationException e, HttpServletRequest request) {
    List<ApiError.Detail> details = new ArrayList<>();
    for (ConstraintViolation<?> v : e.getConstraintViolations()) {
      String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
      String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
      String constraint =
          v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
      details.add(new ApiError.Detail(field, constraint, v.getMessage()));
    }
    return validationFailed(details, request, e);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ApiError> handleMethodValidation(
      HandlerMethodValidationException e, HttpServletRequest request) {
    List<ApiError.Detail> details = new ArrayList<>();
    e.getAllValidationResults()
        .forEach(
            r ->
                r.getResolvableErrors()
                    .forEach(
                        err -> {
                          String code =
                              err.getCodes() != null && err.getCodes().length > 0
                                  ? err.getCodes()[err.getCodes().length - 1]
                                  : "Invalid";
                          details.add(
                              new ApiError.Detail(
                                  r.getMethodParameter().getParameterName(),
                                  code,
                                  err.getDefaultMessage()));
                        }));
    return validationFailed(details, request, e);
  }

  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<ApiError> handleBadRequest(Exception e, HttpServletRequest request) {
    String field = null;
    String code = "Invalid";
    if (e instanceof MissingServletRequestParameterException m) {
      field = m.getParameterName();
      code = "Required";
    } else if (e instanceof MethodArgumentTypeMismatchException m) {
      field = m.getName();
      code = "TypeMismatch";
    }
    List<ApiError.Detail> details =
        field == null
            ? List.of(new ApiError.Detail("body", code, "Malformed request body"))
            : List.of(new ApiError.Detail(field, code, "Invalid value"));
    return validationFailed(details, request, e);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> handleAccessDenied(
      AccessDeniedException e, HttpServletRequest request) {
    return render(ErrorCode.FORBIDDEN, null, null, null, request, e, false);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiError> handleAuthentication(
      AuthenticationException e, HttpServletRequest request) {
    return render(ErrorCode.TOKEN_INVALID, null, null, null, request, e, false);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ApiError> handleNotAcceptable(
      HttpMediaTypeNotAcceptableException e, HttpServletRequest request) {
    return render(ErrorCode.NOT_ACCEPTABLE, null, null, null, request, e, false);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiError> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
    String message =
        e.getSupportedMediaTypes().isEmpty()
            ? null
            : "Expected " + MediaType.toString(e.getSupportedMediaTypes());
    return render(ErrorCode.UNSUPPORTED_MEDIA_TYPE, message, null, null, request, e, false);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
    return ResponseEntity.notFound().build();
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/employees/")
        && "DELETE".equalsIgnoreCase(request.getMethod())) {
      return render(ErrorCode.DIRECT_DELETION_NOT_ALLOWED, null, null, null, request, e, false);
    }
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
    // message is fixed; Throwable.getMessage() never reaches the wire (error-codes.md §3 inv. 2)
    return render(ErrorCode.INTERNAL_ERROR, null, null, null, request, e, true);
  }

  private ResponseEntity<ApiError> validationFailed(
      List<ApiError.Detail> details, HttpServletRequest request, Exception e) {
    String firstField = details.isEmpty() ? null : details.get(0).field();
    return render(ErrorCode.VALIDATION_FAILED, null, firstField, details, request, e, false);
  }

  private ResponseEntity<ApiError> render(
      ErrorCode code,
      @Nullable String message,
      @Nullable String field,
      @Nullable List<ApiError.Detail> details,
      HttpServletRequest request,
      Exception cause,
      boolean unexpected) {
    String traceId = TraceContext.currentTraceId();
    String wireMessage = message != null ? message : code.defaultMessage();
    ApiError body = new ApiError(code.value(), wireMessage, field, traceId, details);

    String path = request.getRequestURI();
    String user = request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();
    if (unexpected) {
      log.error("traceId={} code={} path={} unexpected error", traceId, code.value(), path, cause);
    } else {
      log.warn(
          "traceId={} code={} status={} path={} field={}",
          traceId,
          code.value(),
          code.status().value(),
          path,
          field);
    }
    errorSink.ifPresent(
        sink -> {
          try {
            sink.record(
                traceId,
                code,
                code.status().value(),
                wireMessage,
                unexpected ? describe(cause) : cause.getClass().getSimpleName(),
                path,
                user);
          } catch (RuntimeException sinkFailure) {
            log.error("traceId={} error_log write failed", traceId, sinkFailure);
          }
        });
    return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  private static String describe(Throwable t) {
    StringBuilder sb = new StringBuilder(t.getClass().getName());
    if (t.getMessage() != null) {
      sb.append(": ").append(t.getMessage());
    }
    for (StackTraceElement el : t.getStackTrace()) {
      sb.append("\n  at ").append(el);
      if (sb.length() > 3500) {
        break;
      }
    }
    return sb.toString();
  }
}
