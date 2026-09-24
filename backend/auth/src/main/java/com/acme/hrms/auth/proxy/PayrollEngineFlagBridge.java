package com.acme.hrms.auth.proxy;

import com.acme.hrms.payroll.shadow.PayrollEngineFlag;
import com.acme.hrms.validation.dto.ProxyModule;
import org.springframework.stereotype.Component;

/** Exposes the {@code payroll.engine} proxy flag to payroll-service's shadow reports. */
@Component
public class PayrollEngineFlagBridge implements PayrollEngineFlag {

  private final ProxyFlags flags;

  public PayrollEngineFlagBridge(ProxyFlags flags) {
    this.flags = flags;
  }

  @Override
  public String current() {
    return flags.flag(ProxyModule.PAYROLL_ENGINE).name();
  }
}
