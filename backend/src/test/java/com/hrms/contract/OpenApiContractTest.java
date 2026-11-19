package com.hrms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrms.support.IntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Every implemented /api/v1 route must be declared (path + method) in
 * docs/modernization/openapi-v1.yaml. Phase 3 slices get this check for free.
 */
class OpenApiContractTest extends IntegrationTest {

    private static final Path CONTRACT = Path.of("..", "docs", "modernization", "openapi-v1.yaml");

    @Autowired
    RequestMappingHandlerMapping mappings;

    @SuppressWarnings("unchecked")
    private Set<String> contractOperations() throws IOException {
        try (InputStream in = Files.newInputStream(CONTRACT)) {
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
            Set<String> ops = new TreeSet<>();
            paths.forEach((path, item) -> item.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "patch", "delete").contains(k))
                    .forEach(m -> ops.add(m.toUpperCase() + " /api/v1" + path)));
            return ops;
        }
    }

    private Set<String> implementedOperations() {
        Set<String> ops = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(p -> p.startsWith("/api/v1/"))
                .forEach(p -> info.getMethodsCondition().getMethods()
                        .forEach(m -> ops.add(m.name() + " " + p))));
        return ops;
    }

    @Test
    void everyImplementedRouteIsInTheContract() throws IOException {
        Set<String> implemented = implementedOperations();
        assertThat(implemented).isNotEmpty();
        assertThat(contractOperations()).containsAll(implemented);
    }

    @Test
    void contractCoversEveryModule() throws IOException {
        Set<String> ops = contractOperations();
        for (String prefix : new String[] {"/api/v1/auth/", "/api/v1/leave/", "/api/v1/employees",
                "/api/v1/payroll/", "/api/v1/performance/", "/api/v1/audit/", "/api/v1/reports/"}) {
            assertThat(ops).anyMatch(op -> op.contains(" " + prefix));
        }
    }
}
