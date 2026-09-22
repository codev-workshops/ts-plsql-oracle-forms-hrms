package com.acme.hrms.auth.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.auth.proxy.ProxyFlags;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.validation.dto.ProxyModule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P3 flag state (HRMS_FLAG_EMPLOYEE=NEW): an SSO exchange for {@code employee} is rejected as
 * SSO_MODULE_NOT_LEGACY before the Oracle gateway is consulted, while a still-LEGACY module
 * (payroll) without Oracle answers SSO_LEGACY_UNAVAILABLE. This is the pairing the parallel-run
 * {@code sso.exchange.legacy-module} fixture relies on.
 */
class SsoBridgeServiceFlagTest {

  private static final CallerIdentity CALLER = new CallerIdentity("u1", 1L, Set.of(), "jti-1");

  private static SsoBridgeService serviceWithEmployeeNew() {
    ProxyFlags flags = new ProxyFlags();
    flags.getModules().put(ProxyModule.EMPLOYEE.wire(), ProxyFlags.Flag.NEW.name());
    flags.validate();
    return new SsoBridgeService(null, flags, List.of("10.0.0.0/8"), Clock.systemUTC());
  }

  private static ErrorCode codeOf(SsoBridgeService svc, ProxyModule module) {
    try {
      svc.exchange(CALLER, Instant.now(), module, "10.1.1.1", "10.0.0.5");
      throw new AssertionError("expected HrmsException for " + module);
    } catch (HrmsException e) {
      return e.code();
    }
  }

  @Test
  void employeeNewIsNotLegacyEvenWithoutOracle() {
    assertThat(codeOf(serviceWithEmployeeNew(), ProxyModule.EMPLOYEE))
        .isEqualTo(ErrorCode.SSO_MODULE_NOT_LEGACY);
  }

  @Test
  void payrollStillLegacyAnswersLegacyUnavailableWithoutOracle() {
    assertThat(codeOf(serviceWithEmployeeNew(), ProxyModule.PAYROLL))
        .isEqualTo(ErrorCode.SSO_LEGACY_UNAVAILABLE);
  }

  @Test
  void authIsNeverLegacy() {
    assertThatThrownBy(
            () ->
                serviceWithEmployeeNew()
                    .exchange(CALLER, Instant.now(), ProxyModule.AUTH, "10.1.1.1", "10.0.0.5"))
        .isInstanceOf(HrmsException.class);
  }
}
