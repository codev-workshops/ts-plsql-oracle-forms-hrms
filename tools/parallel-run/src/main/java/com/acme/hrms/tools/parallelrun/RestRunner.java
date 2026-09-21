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

  public Outcome run(Scenario s) throws IOException, InterruptedException {
    for (RestCall setup : s.target().setup()) {
      call(setup);
    }
    HttpResponse<String> resp = call(s.target());
    return project(resp, s.expect().fields().keySet());
  }

  private HttpResponse<String> call(RestCall c) throws IOException, InterruptedException {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(baseUrl + c.path()))
            .header("Content-Type", "application/json");
    if (c.auth() != null) {
      b.header("Authorization", "Bearer " + tokenFor(c.auth()));
    }
    if (c.useRefreshCookie() && refreshCookie != null) {
      b.header("Cookie", refreshCookie);
    }
    String body = c.body() == null ? "" : JSON.writeValueAsString(c.body());
    b.method(c.method(), HttpRequest.BodyPublishers.ofString(body));
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
    Map<String, String> fields = new LinkedHashMap<>();
    for (String w : wanted) {
      JsonNode v = n.path(w);
      if (v.isMissingNode() && w.equals("emp_id")) {
        v = n.path("user").path("empId");
      }
      fields.put(w, v.isMissingNode() ? null : v.asText());
    }
    return Outcome.ok(fields);
  }
}
