package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the target REST API (through the proxy, so module flags apply) and projects the JSON
 * response onto an {@link Outcome}: {@code ApiError.code} on 4xx/5xx, selected fields on 2xx.
 * Access tokens and the hrms_refresh cookie are captured from login responses so a scenario can
 * authenticate as the seed user or replay a refresh.
 */
public final class RestRunner {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;
  private final String password;
  private final Map<String, String> accessTokens = new HashMap<>();
  private String refreshCookie;

  public RestRunner(String baseUrl, String seedPassword) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.password = seedPassword;
  }

  /**
   * Ids captured from setup responses; paths may reference them as {cycleId}, {reviewId}, {goalId},
   * {requestId}.
   */
  static final List<String> CAPTURED_IDS =
      List.of(
          "cycleId",
          "reviewId",
          "goalId",
          "requestId",
          "id",
          "dependentId",
          "contactId",
          "runId",
          "jobId");

  /**
   * Setup calls to a payroll {@code /status} resource or a leave batch job ({@link
   * LeaveScenarios#JOB_PATH}) are re-polled while the asynchronous Spring Batch job reports {@code
   * CALCULATING} / {@code RUNNING} (COMPONENT_MAPPING.md §4: calculate returns 202; P5 openapi:
   * accrual / carryover return 202 + BatchRunResult).
   */
  static final String ASYNC_STATUS_SUFFIX = "/status";

  private static final Set<String> ASYNC_PENDING = Set.of("CALCULATING", "RUNNING");
  private static final int ASYNC_MAX_POLLS = 120;
  private static final long ASYNC_POLL_MILLIS = 500;

  /** Context key holding the last {@code ETag} seen; PUTs send it back as {@code If-Match}. */
  static final String ETAG = "etag";

  private final Map<String, String> context = new LinkedHashMap<>();

  public Outcome run(Scenario s) throws IOException, InterruptedException {
    context.clear();
    for (RestCall setup : s.target().setup()) {
      HttpResponse<String> resp = call(setup);
      if (isAsyncPoll(setup)) {
        resp = awaitSettled(setup, resp);
      }
      capture(resp);
    }
    HttpResponse<String> resp = call(s.target());
    return project(resp, s.expect().fields().keySet());
  }

  static boolean isAsyncPoll(RestCall c) {
    return c.path().endsWith(ASYNC_STATUS_SUFFIX) || c.path().equals(LeaveScenarios.JOB_PATH);
  }

  private HttpResponse<String> awaitSettled(RestCall status, HttpResponse<String> first)
      throws IOException, InterruptedException {
    HttpResponse<String> resp = first;
    for (int i = 0; i < ASYNC_MAX_POLLS && resp.statusCode() < 300; i++) {
      if (!ASYNC_PENDING.contains(JSON.readTree(resp.body()).path("status").asText())) {
        return resp;
      }
      Thread.sleep(ASYNC_POLL_MILLIS);
      resp = call(status);
    }
    return resp;
  }

  private void capture(HttpResponse<String> resp) throws IOException {
    if (resp.statusCode() >= 300 || resp.body().isBlank()) {
      return;
    }
    resp.headers().firstValue("ETag").ifPresent(v -> context.put(ETAG, v));
    JsonNode n = JSON.readTree(resp.body());
    if (n.isArray() && !n.isEmpty()) {
      n = n.get(0);
    }
    for (String id : CAPTURED_IDS) {
      if (n.hasNonNull(id)) {
        context.put(id, n.get(id).asText());
      }
    }
  }

  static String resolve(String path, Map<String, String> context) {
    String out = path;
    for (Map.Entry<String, String> e : context.entrySet()) {
      out = out.replace("{" + e.getKey() + "}", e.getValue());
    }
    return out;
  }

  private HttpResponse<String> call(RestCall c) throws IOException, InterruptedException {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(baseUrl + resolve(c.path(), context)))
            .header("Content-Type", "application/json");
    if (c.auth() != null) {
      b.header("Authorization", "Bearer " + tokenFor(c.auth()));
    }
    if (c.useRefreshCookie() && refreshCookie != null) {
      b.header("Cookie", refreshCookie);
    }
    if ("PUT".equals(c.method()) && context.containsKey(ETAG)) {
      b.header("If-Match", context.get(ETAG));
    }
    if (c.body() == null) {
      b.method(c.method(), HttpRequest.BodyPublishers.noBody());
    } else {
      b.method(c.method(), HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(c.body())));
    }
    HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    resp.headers().allValues("Set-Cookie").stream()
        .filter(v -> v.startsWith("hrms_refresh="))
        .findFirst()
        .ifPresent(v -> refreshCookie = v.split(";", 2)[0]);
    if (c.path().equals("/api/auth/login") && resp.statusCode() == 200) {
      JsonNode n = JSON.readTree(resp.body());
      accessTokens.put(String.valueOf(c.body().get("username")), n.path("accessToken").asText());
    }
    return resp;
  }

  private String tokenFor(String user) throws IOException, InterruptedException {
    if (!accessTokens.containsKey(user)) {
      call(
          new RestCall(
              "POST",
              "/api/auth/login",
              Map.of("username", user, "password", password),
              null,
              false,
              List.of()));
    }
    String t = accessTokens.get(user);
    if (t == null) {
      throw new IOException("could not log in as " + user);
    }
    return t;
  }

  static Outcome project(HttpResponse<String> resp, java.util.Set<String> wanted)
      throws IOException {
    JsonNode n = resp.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(resp.body());
    if (resp.statusCode() >= 400) {
      return Outcome.error(n.path("code").asText("HTTP_" + resp.statusCode()));
    }
    if (n.isArray()) {
      // List endpoints project their first element unless a wanted field explicitly selects an
      // element: by index (salary history uses [1].endDate to inspect the closed prior row) or
      // by attribute (leave balances use [leaveTypeId=1].accrued to pick the PTO row).
      boolean selected = wanted.stream().anyMatch(w -> w.matches(ELEMENT_SELECTOR));
      if (!selected) {
        n = n.isEmpty() ? JSON.createObjectNode() : n.get(0);
      }
    }
    Map<String, String> fields = new LinkedHashMap<>();
    for (String w : wanted) {
      JsonNode v = w.contains(".") || w.contains("[") ? select(n, w) : n.path(w);
      if (v.isMissingNode() && w.equals("emp_id")) {
        v = n.path("user").path("empId");
      }
      fields.put(w, v.isMissingNode() ? null : text(v));
    }
    return Outcome.ok(fields);
  }

  /** A wanted field that starts with an element selector on the root array: {@code [..].x}. */
  static final String ELEMENT_SELECTOR = "\\[[^\\]]+\\]\\..+";

  /**
   * Dotted / indexed / filtered selector over the response, e.g. {@code summary.matched}, {@code
   * content[0].errorCode} or {@code [leaveTypeId=1].accrued} (first array element whose attribute
   * renders as the given text); a missing hop yields a missing node.
   */
  static JsonNode select(JsonNode root, String selector) {
    JsonNode cur = root;
    for (String hop : selector.split("\\.")) {
      int bracket = hop.indexOf('[');
      String name = bracket < 0 ? hop : hop.substring(0, bracket);
      if (!name.isEmpty()) {
        cur = cur.path(name);
      }
      while (bracket >= 0) {
        int close = hop.indexOf(']', bracket);
        cur = element(cur, hop.substring(bracket + 1, close));
        bracket = hop.indexOf('[', close);
      }
    }
    return cur;
  }

  private static JsonNode element(JsonNode array, String spec) {
    if (!array.isArray()) {
      return JSON.missingNode();
    }
    int eq = spec.indexOf('=');
    if (eq < 0) {
      int index = Integer.parseInt(spec);
      return index < array.size() ? array.get(index) : JSON.missingNode();
    }
    String attr = spec.substring(0, eq);
    String value = spec.substring(eq + 1);
    for (JsonNode e : array) {
      if (e.hasNonNull(attr) && text(e.get(attr)).equals(value)) {
        return e;
      }
    }
    return JSON.missingNode();
  }

  /** JSON numbers as Oracle's NUMBER getString renders them: no trailing zeros (5.00 → 5). */
  static String text(JsonNode v) {
    return v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asText();
  }
}
