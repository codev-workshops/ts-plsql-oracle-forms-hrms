package com.acme.hrms.auth.proxy;

import com.acme.hrms.validation.dto.ProxyModule;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-module routing flags (frozen vocabulary, contracts/p0-foundation/README.md). The reverse
 * proxy (proxy/nginx.conf.template) and the SSO bridge read the same values so a module cannot be
 * NEW for the proxy and LEGACY for the bridge.
 */
@ConfigurationProperties(prefix = "hrms.proxy")
public class ProxyFlags {

  public enum Flag {
    LEGACY,
    NEW_READONLY,
    NEW,
    JAVA
  }

  /** Keyed by wire name ({@code "[payroll.engine]": LEGACY} in YAML); values are {@link Flag}. */
  private final Map<String, String> modules = new LinkedHashMap<>();

  public ProxyFlags() {
    modules.put(ProxyModule.AUTH.wire(), Flag.NEW.name());
  }

  public Map<String, String> getModules() {
    return modules;
  }

  public Flag flag(ProxyModule module) {
    String raw = modules.get(module.wire());
    return raw == null ? Flag.LEGACY : Flag.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }

  public Map<ProxyModule, Flag> resolved() {
    Map<ProxyModule, Flag> out = new EnumMap<>(ProxyModule.class);
    for (ProxyModule m : ProxyModule.values()) {
      out.put(m, flag(m));
    }
    return out;
  }

  /** Only LEGACY (and the read-only coexistence mode) still need an Oracle Forms session. */
  public boolean needsFormsSession(ProxyModule module) {
    if (module == ProxyModule.AUTH) {
      return false;
    }
    Flag f = flag(module);
    return f == Flag.LEGACY || f == Flag.NEW_READONLY;
  }

  public void validate() {
    if (flag(ProxyModule.AUTH) != Flag.NEW) {
      throw new IllegalStateException("hrms.proxy.modules.auth must be NEW from Phase 0");
    }
    Flag engine = flag(ProxyModule.PAYROLL_ENGINE);
    if (engine != Flag.LEGACY && engine != Flag.JAVA) {
      throw new IllegalStateException("payroll.engine must be LEGACY|JAVA");
    }
    for (String key : modules.keySet()) {
      ProxyModule.fromWire(key);
    }
    for (Map.Entry<ProxyModule, Flag> e : resolved().entrySet()) {
      if (e.getKey() != ProxyModule.EMPLOYEE && e.getValue() == Flag.NEW_READONLY) {
        throw new IllegalStateException(e.getKey().wire() + " does not support NEW_READONLY");
      }
      if (e.getKey() != ProxyModule.PAYROLL_ENGINE && e.getValue() == Flag.JAVA) {
        throw new IllegalStateException(e.getKey().wire() + " does not support JAVA");
      }
    }
  }
}
