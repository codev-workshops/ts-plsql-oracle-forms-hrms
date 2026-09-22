package com.acme.hrms.tools.parallelrun;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The reverse-proxy flags the target backend was started with ({@code HRMS_FLAG_<MODULE>},
 * contracts/p0-foundation/README.md vocabulary), so phase-dependent scenarios can pick the module
 * that is still LEGACY instead of hard-coding the current phase. Read from the runner's environment
 * (export the same variables as for the backend) or overridden with {@code --flags
 * payroll=NEW,payroll.engine=JAVA}.
 */
public record TargetFlags(Map<String, String> modules) {

  /** Modules in cutover order (P1 .. P5); {@code auth} is NEW from Phase 0. */
  static final List<String> CUTOVER_ORDER =
      List.of("performance", "leave", "employee", "payroll", "reporting");

  /** Forms module the auth-service opens for each legacy proxy module (SsoBridgeService). */
  static final Map<String, String> FORMS_MODULE =
      Map.of(
          "employee", "HRMS_EMPLOYEE",
          "payroll", "HRMS_PAYROLL",
          "leave", "HRMS_LEAVE",
          "performance", "HRMS_PERFORMANCE",
          "reporting", "HRMS_MENU");

  public TargetFlags {
    Map<String, String> normalized = new LinkedHashMap<>();
    modules.forEach((k, v) -> normalized.put(k.trim().toLowerCase(Locale.ROOT), normalize(v)));
    modules = Map.copyOf(normalized);
  }

  public static TargetFlags of(Map<String, String> modules) {
    return new TargetFlags(modules);
  }

  /** Every module LEGACY except auth, i.e. the backend's defaults. */
  public static TargetFlags legacyDefaults() {
    return new TargetFlags(Map.of());
  }

  public static TargetFlags fromEnv() {
    return fromEnv(System.getenv());
  }

  static TargetFlags fromEnv(Map<String, String> env) {
    Map<String, String> modules = new LinkedHashMap<>();
    for (String module : CUTOVER_ORDER) {
      String value = env.get(envName(module));
      if (value != null && !value.isBlank()) {
        modules.put(module, value);
      }
    }
    String engine = env.get(envName("payroll.engine"));
    if (engine != null && !engine.isBlank()) {
      modules.put("payroll.engine", engine);
    }
    return new TargetFlags(modules);
  }

  /** {@code --flags payroll=NEW,payroll.engine=JAVA} on top of {@code this}. */
  public TargetFlags with(String spec) {
    Map<String, String> merged = new LinkedHashMap<>(modules);
    for (String pair : spec.split(",")) {
      if (pair.isBlank()) {
        continue;
      }
      String[] kv = pair.split("=", 2);
      if (kv.length != 2) {
        throw new IllegalArgumentException("--flags expects module=FLAG pairs, got '" + pair + "'");
      }
      merged.put(kv[0], kv[1]);
    }
    return new TargetFlags(merged);
  }

  static String envName(String module) {
    return "HRMS_FLAG_" + module.toUpperCase(Locale.ROOT).replace('.', '_');
  }

  public String flag(String module) {
    if ("auth".equals(module)) {
      return "NEW";
    }
    return modules.getOrDefault(module, "LEGACY");
  }

  /** Mirrors ProxyFlags.needsFormsSession: LEGACY and the read-only coexistence mode. */
  public boolean needsFormsSession(String module) {
    String f = flag(module);
    return "LEGACY".equals(f) || "NEW_READONLY".equals(f);
  }

  /** First module in cutover order that still opens a Forms session; empty once all are NEW. */
  public Optional<String> firstLegacyModule() {
    return CUTOVER_ORDER.stream().filter(this::needsFormsSession).findFirst();
  }

  private static String normalize(String flag) {
    String f = flag.trim().toUpperCase(Locale.ROOT);
    if (!List.of("LEGACY", "NEW_READONLY", "NEW", "JAVA").contains(f)) {
      throw new IllegalArgumentException(
          "Unknown flag '" + flag + "' (LEGACY|NEW_READONLY|NEW|JAVA)");
    }
    return f;
  }
}
