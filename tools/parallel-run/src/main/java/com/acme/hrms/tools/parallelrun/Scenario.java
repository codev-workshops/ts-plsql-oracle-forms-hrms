package com.acme.hrms.tools.parallelrun;

import java.util.List;
import java.util.Map;

/**
 * One Level-2 parallel-run scenario (TEST_STRATEGY.md §2.2): the same business action driven
 * against the legacy oracle (utPLSQL test or PL/SQL call) and the target REST API, with the
 * observable outcome projected onto a common shape so {@link DiffReport} can compare them.
 *
 * @param id stable id, e.g. {@code auth.login.ok}
 * @param module proxy module the scenario belongs to
 * @param legacy how to obtain the legacy outcome
 * @param target how to obtain the target outcome
 * @param expect expected {@link Outcome} (the frozen contract); both runners must match it
 */
public record Scenario(
    String id, String module, LegacyCall legacy, RestCall target, Outcome expect) {

  /** PL/SQL block executed against Oracle; binds resolve from the scenario context. */
  public record LegacyCall(String plsql, List<String> outBinds) {}

  /** HTTP request against the target; {@code auth} = pre-login as this user first. */
  public record RestCall(
      String method,
      String path,
      Map<String, Object> body,
      String auth,
      boolean useRefreshCookie,
      List<RestCall> setup) {
    public RestCall withSetup(List<RestCall> newSetup) {
      return new RestCall(method, path, body, auth, useRefreshCookie, newSetup);
    }
  }

  /**
   * Common observable: legacy error code (or {@code null} for success) and a small set of named
   * fields both sides can produce.
   */
  public record Outcome(String errorCode, Map<String, String> fields) {
    public static Outcome ok(Map<String, String> fields) {
      return new Outcome(null, fields);
    }

    public static Outcome error(String code) {
      return new Outcome(code, Map.of());
    }
  }
}
