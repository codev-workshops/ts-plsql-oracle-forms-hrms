package com.acme.hrms.auth.sso;

import com.acme.hrms.auth.jwt.JwtAuthenticationFilter;
import com.acme.hrms.auth.jwt.JwtService;
import com.acme.hrms.auth.sso.SsoBridgeService.Exchange;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.validation.dto.SsoExchangeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST /legacy/sso/exchange - proxy-only (the CIDR check is done in the service). */
@RestController
public class SsoController {

  public record SsoExchangeResponse(
      String formsModule, String otherparams, long legacySessionId, OffsetDateTime expiresAt) {}

  private final SsoBridgeService bridge;
  private final JwtService jwt;

  public SsoController(SsoBridgeService bridge, JwtService jwt) {
    this.bridge = bridge;
    this.jwt = jwt;
  }

  @PostMapping("/legacy/sso/exchange")
  @PreAuthorize("isAuthenticated()")
  public SsoExchangeResponse exchangeJwtForFormsSession(
      @Valid @RequestBody SsoExchangeRequest body, HttpServletRequest request) {
    String raw = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_RAW_TOKEN);
    Instant expiry = jwt.expiryOf(raw == null ? "" : raw).orElse(bridge.now());
    Exchange ex =
        bridge.exchange(
            CurrentCaller.require(),
            expiry,
            body.getModule(),
            body.getClientIp(),
            request.getRemoteAddr());
    return new SsoExchangeResponse(
        ex.formsModule(),
        ex.otherparams(),
        ex.legacySessionId(),
        ex.expiresAt().atOffset(ZoneOffset.UTC));
  }
}
