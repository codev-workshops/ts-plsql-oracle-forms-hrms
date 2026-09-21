package com.acme.hrms.auth.sso;

import com.acme.hrms.auth.proxy.ProxyFlags;
import com.acme.hrms.auth.sso.LegacySessionGateway.LegacyEmployee;
import com.acme.hrms.auth.sso.LegacySessionGateway.LegacyUnavailableException;
import com.acme.hrms.common.error.EmployeeNotFoundException;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.validation.dto.ProxyModule;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * JWT → Oracle Forms session (CUTOVER_PLAN.md §2 rule 3). The caller's identity is the JWT's {@code
 * empId}; the request carries only the target module and the end-user IP.
 */
public class SsoBridgeService {

  public record Exchange(
      String formsModule, String otherparams, long legacySessionId, Instant expiresAt) {}

  @Nullable private final LegacySessionGateway gateway;
  private final ProxyFlags flags;
  private final List<IpAddressMatcher> proxyMatchers;
  private final Clock clock;

  public SsoBridgeService(
      @Nullable LegacySessionGateway gateway,
      ProxyFlags flags,
      List<String> proxyCidrs,
      Clock clock) {
    this.gateway = gateway;
    this.flags = flags;
    this.proxyMatchers = proxyCidrs.stream().map(IpAddressMatcher::new).toList();
    this.clock = clock;
  }

  /** Forms module per proxy flag key; HRMS_LOGIN is never returned. */
  public static String formsModuleFor(ProxyModule module) {
    return switch (module) {
      case EMPLOYEE -> "HRMS_EMPLOYEE";
      case PAYROLL, PAYROLL_ENGINE -> "HRMS_PAYROLL";
      case LEAVE -> "HRMS_LEAVE";
      case PERFORMANCE -> "HRMS_PERFORMANCE";
      case REPORTING, AUTH -> "HRMS_MENU";
    };
  }

  public boolean isInternalProxy(String remoteAddr) {
    return proxyMatchers.stream().anyMatch(m -> m.matches(remoteAddr));
  }

  public Exchange exchange(
      CallerIdentity caller,
      Instant tokenExpiry,
      ProxyModule module,
      String clientIp,
      String remoteAddr) {
    if (!isInternalProxy(remoteAddr)) {
      throw new HrmsException(
          ErrorCode.SSO_MODULE_NOT_LEGACY, "SSO exchange is reserved for the reverse proxy", null);
    }
    if (!flags.needsFormsSession(module)) {
      throw new HrmsException(ErrorCode.SSO_MODULE_NOT_LEGACY);
    }
    if (gateway == null) {
      throw new HrmsException(ErrorCode.SSO_LEGACY_UNAVAILABLE);
    }
    try {
      LegacyEmployee employee =
          gateway.findActiveEmployee(caller.empId()).orElseThrow(EmployeeNotFoundException::new);
      String formsModule = formsModuleFor(module);
      long sessionId =
          gateway.openFormsSession(employee.email(), clientIp, formsModule, caller.jti());
      String otherparams =
          "session_id="
              + sessionId
              + "&current_user="
              + URLEncoder.encode(employee.email(), StandardCharsets.UTF_8)
              + "&current_emp_id="
              + employee.empId();
      return new Exchange(formsModule, otherparams, sessionId, tokenExpiry);
    } catch (LegacyUnavailableException e) {
      throw new HrmsException(
          ErrorCode.SSO_LEGACY_UNAVAILABLE,
          ErrorCode.SSO_LEGACY_UNAVAILABLE.defaultMessage(),
          null,
          e);
    }
  }

  public Instant now() {
    return clock.instant();
  }
}
