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
 * application must equal the set declared in contracts/p0-foundation/openapi.yaml – nothing
 * missing, nothing undocumented.
 */
class OpenApiContractTest extends AuthApiTestBase {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @Test
  void servedOperationsEqualTheFrozenContractExactly() throws Exception {
    Path spec = HrmsPostgres.repoRoot().resolve("contracts/p0-foundation/openapi.yaml");
    Map<String, Object> doc = new Yaml().load(Files.readString(spec));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
    Set<String> contract = new TreeSet<>();
    paths.forEach(
        (path, ops) ->
            ops.keySet().stream()
                .filter(m -> Set.of("get", "post", "put", "patch", "delete").contains(m))
                .forEach(m -> contract.add(m.toUpperCase() + " " + path)));

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
    assertThat(contract).hasSize(11);
  }
}
