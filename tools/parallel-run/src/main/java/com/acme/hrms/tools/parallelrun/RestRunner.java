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
      List.of("cycleId", "reviewId", "goalId", "requestId", "id", "dependentId", "contactId");

  /** Context key holding the last {@code ETag} seen; PUTs send it back as {@code If-Match}. */
  static final String ETAG = "etag";

  private final Map<String, String> context = new LinkedHashMap<>();

  public Outcome run(Scenario s) throws IOException, InterruptedException {
    context.clear();
    for (RestCall setup : s.target().setup()) {
      capture(call(setup));
    }
    HttpResponse<String> resp = call(s.target());
    return project(resp, s.expect().fields().keySet());
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
      // array index (salary history uses [1].endDate to inspect the closed prior row).
      boolean indexed = wanted.stream().anyMatch(w -> w.matches("\\[\\d+\\]\\..+"));
      if (!indexed) {
        n = n.isEmpty() ? JSON.createObjectNode() : n.get(0);
      }
    }
    Map<String, String> fields = new LinkedHashMap<>();
    for (String w : wanted) {
      JsonNode v = n.path(w);
      if (w.matches("\\[\\d+\\]\\..+")) {
        int dot = w.indexOf('.');
        int index = Integer.parseInt(w.substring(1, dot - 1));
        String key = w.substring(dot + 1);
        v = n.isArray() && index < n.size() ? n.get(index).path(key) : JSON.missingNode();
      }
      if (v.isMissingNode() && w.equals("emp_id")) {
        v = n.path("user").path("empId");
      }
      fields.put(w, v.isMissingNode() ? null : text(v));
    }
    return Outcome.ok(fields);
  }

  /** JSON numbers as Oracle's NUMBER getString renders them: no trailing zeros (5.00 → 5). */
  static String text(JsonNode v) {
    return v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asText();
  }
}
