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
 * performance) – nothing missing, nothing undocumented.
 */
class OpenApiContractTest extends AuthApiTestBase {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @Test
  void servedOperationsEqualTheFrozenContractExactly() throws Exception {
    Set<String> p0 = operations("p0-foundation");
    Set<String> p1 = operations("p1-performance");
    assertThat(p0).hasSize(11);
    assertThat(p1).hasSize(18);
    Set<String> contract = new TreeSet<>(p0);
    contract.addAll(p1);
    assertThat(contract).hasSize(29);

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
    Path spec = HrmsPostgres.repoRoot().resolve("contracts/" + contractDir + "/openapi.yaml");
    Map<String, Object> doc = new Yaml().load(Files.readString(spec));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
    Set<String> ops = new TreeSet<>();
    paths.forEach(
        (path, methods) ->
            methods.keySet().stream()
                .filter(m -> Set.of("get", "post", "put", "patch", "delete").contains(m))
                .forEach(m -> ops.add(m.toUpperCase() + " " + path)));
    return ops;
  }
}
