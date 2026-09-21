package com.acme.hrms.common.trace;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.MDC;

/** Per-request trace id (W3C {@code traceparent} trace-id when present, else random 16 bytes). */
public final class TraceContext {

  public static final String MDC_KEY = "traceId";
  private static final SecureRandom RANDOM = new SecureRandom();

  private TraceContext() {}

  public static String currentTraceId() {
    String id = MDC.get(MDC_KEY);
    return id != null ? id : "00000000000000000000000000000000";
  }

  public static String newTraceId() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** Extracts the 32-hex trace-id from a W3C {@code traceparent} header, or null. */
  public static String fromTraceparent(String traceparent) {
    if (traceparent == null) {
      return null;
    }
    String[] parts = traceparent.trim().split("-");
    if (parts.length >= 3 && parts[1].matches("[0-9a-f]{32}") && !parts[1].matches("0{32}")) {
      return parts[1];
    }
    return null;
  }
}
