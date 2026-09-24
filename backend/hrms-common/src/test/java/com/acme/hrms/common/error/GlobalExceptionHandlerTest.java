package com.acme.hrms.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.acme.hrms.common.trace.TraceContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

class GlobalExceptionHandlerTest {

  private final ErrorSink sink = mock(ErrorSink.class);
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler(Optional.of(sink));
  private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x");

  @BeforeEach
  void trace() {
    MDC.put(TraceContext.MDC_KEY, "0af7651916cd43dd8448eb211c80319c");
  }

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  void hrmsExceptionCarriesLegacyCodeAsString() {
    ResponseEntity<ApiError> r =
        handler.handleHrms(
            new PasswordPolicyException(
                ErrorCode.PASSWORD_NO_DIGIT, "Password must contain a number"),
            request);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().code()).isEqualTo("-20312");
    assertThat(r.getBody().field()).isEqualTo("newPassword");
    assertThat(r.getBody().traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(r.getBody().details()).isNull();
    verify(sink)
        .record(
            eq("0af7651916cd43dd8448eb211c80319c"),
            eq(ErrorCode.PASSWORD_NO_DIGIT),
            eq(400),
            eq("Password must contain a number"),
            anyString(),
            eq("/api/x"),
            isNull());
  }

  @Test
  void unexpectedErrorNeverLeaksMessage() {
    ResponseEntity<ApiError> r =
        handler.handleUnexpected(
            new IllegalStateException("ORA-00942: table or view does not exist"), request);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(r.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(r.getBody().message())
        .doesNotContain("ORA-")
        .isEqualTo("An unexpected error occurred");
    verify(sink)
        .record(
            anyString(), eq(ErrorCode.INTERNAL_ERROR), anyInt(), anyString(), any(), any(), any());
  }

  @Test
  void securityExceptionsMap() {
    assertThat(handler.handleAccessDenied(new AccessDeniedException("x"), request).getBody().code())
        .isEqualTo("FORBIDDEN");
    assertThat(
            handler.handleAuthentication(new BadCredentialsException("x"), request).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            handler
                .handleAuthentication(new BadCredentialsException("x"), request)
                .getBody()
                .code())
        .isEqualTo("TOKEN_INVALID");
  }

  @Test
  void unsupportedRequestContentTypeIs415() {
    ResponseEntity<ApiError> r =
        handler.handleMediaTypeNotSupported(
            new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.MULTIPART_FORM_DATA)),
            request);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(r.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(r.getBody().message()).isEqualTo("Expected multipart/form-data");
    assertThat(r.getBody().traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    verify(sink)
        .record(
            eq("0af7651916cd43dd8448eb211c80319c"),
            eq(ErrorCode.UNSUPPORTED_MEDIA_TYPE),
            eq(415),
            eq("Expected multipart/form-data"),
            eq("HttpMediaTypeNotSupportedException"),
            eq("/api/x"),
            isNull());
  }

  @Test
  void missingMultipartPartIs400ValidationFailedOnField() {
    ResponseEntity<ApiError> r =
        handler.handleBadRequest(new MissingServletRequestPartException("file"), request);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().code()).isEqualTo("VALIDATION_FAILED");
    assertThat(r.getBody().field()).isEqualTo("file");
    assertThat(r.getBody().traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(r.getBody().details()).hasSize(1);
    assertThat(r.getBody().details().get(0).field()).isEqualTo("file");
    assertThat(r.getBody().details().get(0).code()).isEqualTo("Required");
    verify(sink)
        .record(
            eq("0af7651916cd43dd8448eb211c80319c"),
            eq(ErrorCode.VALIDATION_FAILED),
            eq(400),
            anyString(),
            eq("MissingServletRequestPartException"),
            eq("/api/x"),
            isNull());
  }
}
