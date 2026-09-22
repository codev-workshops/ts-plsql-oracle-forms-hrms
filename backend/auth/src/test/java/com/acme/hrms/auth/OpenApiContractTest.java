package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Generate/compare gate for the frozen contract: the set of (method, path) pairs served by the
 * application must equal the union of the sets declared in the frozen contracts (P0 foundation + P1
 * performance + P2 leave + P3 employee) – nothing missing, nothing undocumented. P3 re-declares
 * P0's {@code GET/POST /api/employees}; a set union keeps them single. Operations the P2 contract
 * declares with {@code x-deferred: true} (the P5 admin batch routes) must NOT be served.
 */
class OpenApiContractTest extends AuthApiTestBase {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @Test
  void servedOperationsEqualTheFrozenContractExactly() throws Exception {
    Set<String> p0 = operations("p0-foundation");
    Set<String> p1 = operations("p1-performance");
    Set<String> p2 = operations("p2-leave");
    Set<String> p2Deferred = deferredOperations("p2-leave");
    Set<String> p3 = operations("p3-employee", "/api/employees");
    assertThat(p0).hasSize(11);
    assertThat(p1).hasSize(18);
    assertThat(p2).hasSize(16);
    assertThat(p2Deferred).hasSize(4).allMatch(op -> op.startsWith("POST /api/leave/admin/"));
    assertThat(p3).hasSize(16).doesNotContain("DELETE /api/employees/{id}");
    Set<String> contract = new TreeSet<>(p0);
    contract.addAll(p1);
    contract.addAll(p2);
    contract.addAll(p3);
    contract.removeAll(p2Deferred);
    assertThat(contract).hasSize(55);

    Set<String> served = new TreeSet<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
      if (e.getValue().getBeanType().getName().startsWith("org.springframework")) {
        continue; // BasicErrorController etc. – framework, not API surface
      }
      Set<String> patterns = e.getKey().getPathPatternsCondition().getPatternValues();
      var methods = e.getKey().getMethodsCondition().getMethods();
      for (String p : patterns) {
        for (var m : methods) {
          served.add(m.name() + " " + p);
        }
      }
    }
    assertThat(served).containsExactlyElementsOf(contract);
  }

  private static Set<String> operations(String contractDir) throws Exception {
    return operations(contractDir, false, null);
  }

  private static Set<String> deferredOperations(String contractDir) throws Exception {
    return operations(contractDir, true, null);
  }

  private static Set<String> operations(String contractDir, String pathPrefix) throws Exception {
    return operations(contractDir, false, pathPrefix);
  }

  private static Set<String> operations(String contractDir, boolean onlyDeferred, String pathPrefix)
      throws Exception {
    Path spec = HrmsPostgres.repoRoot().resolve("contracts/" + contractDir + "/openapi.yaml");
    Map<String, Object> doc = new Yaml().load(Files.readString(spec));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
    Set<String> ops = new TreeSet<>();
    paths.forEach(
        (path, methods) ->
            methods.forEach(
                (m, op) -> {
                  if (!Set.of("get", "post", "put", "patch", "delete").contains(m)) {
                    return;
                  }
                  if (pathPrefix != null && !path.startsWith(pathPrefix)) {
                    return;
                  }
                  boolean deferred =
                      op instanceof Map<?, ?> o && Boolean.TRUE.equals(o.get("x-deferred"));
                  if (!onlyDeferred || deferred) {
                    ops.add(m.toUpperCase() + " " + path);
                  }
                }));
    return ops;
  }
}
