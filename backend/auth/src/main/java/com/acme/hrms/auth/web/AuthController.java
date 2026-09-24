package com.acme.hrms.auth.web;

import com.acme.hrms.auth.config.AuthProperties;
import com.acme.hrms.auth.jwt.JwtAuthenticationFilter;
import com.acme.hrms.auth.service.AuthService;
import com.acme.hrms.auth.service.AuthService.TokenPair;
import com.acme.hrms.auth.web.AuthDtos.CurrentUser;
import com.acme.hrms.auth.web.AuthDtos.TokenResponse;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.validation.dto.ChangePasswordRequest;
import com.acme.hrms.validation.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/auth/* - the five frozen operations, nothing else. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService auth;
  private final AuthProperties props;

  public AuthController(AuthService auth, AuthProperties props) {
    this.auth = auth;
    this.props = props;
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    TokenPair pair = auth.login(body.getUsername(), body.getPassword(), clientIp(request));
    return withRefreshCookie(pair);
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = "hrms_refresh", required = false) @Nullable String refreshToken) {
    return withRefreshCookie(auth.refresh(refreshToken));
  }

  @PostMapping("/logout")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    String raw = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_RAW_TOKEN);
    auth.logout(CurrentCaller.require(), raw == null ? "" : raw, clientIp(request));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .build();
  }

  @GetMapping("/me")
  @PreAuthorize("isAuthenticated()")
  public CurrentUser me() {
    return auth.me(CurrentCaller.require());
  }

  @PutMapping("/password")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
    auth.changePassword(CurrentCaller.require(), body.getCurrentPassword(), body.getNewPassword());
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<TokenResponse> withRefreshCookie(TokenPair pair) {
    Duration maxAge = Duration.between(Instant.now(), pair.refreshExpiresAt());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(pair.refreshToken(), maxAge).toString())
        .body(pair.response());
  }

  private ResponseCookie cookie(String value, Duration maxAge) {
    AuthProperties.Cookie c = props.cookie();
    return ResponseCookie.from(c.name(), value)
        .httpOnly(true)
        .secure(c.secure())
        .sameSite(c.sameSite())
        .path(c.path())
        .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
        .build();
  }

  /** First hop of X-Forwarded-For (set by the reverse proxy), else the socket address. */
  static String clientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
