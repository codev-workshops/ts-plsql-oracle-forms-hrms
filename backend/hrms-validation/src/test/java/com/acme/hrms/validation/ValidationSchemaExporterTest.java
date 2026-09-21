package com.acme.hrms.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.validation.export.ValidationSchemaExporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * TEST_STRATEGY.md §2.1 snapshot: the exporter output differs from the frozen hand-written
 * frontend/src/generated/validation-schema.json only in generatorVersion and sourceHash.
 */
class ValidationSchemaExporterTest {

  @Test
  void matchesFrozenSnapshotExceptProvenanceFields() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("frontend/src/generated/validation-schema.json"))) {
      root = root.getParent();
    }
    ObjectMapper mapper = new ObjectMapper();
    JsonNode frozen =
        mapper.readTree(root.resolve("frontend/src/generated/validation-schema.json").toFile());
    ObjectNode generated = new ValidationSchemaExporter().export("0.1.0-SNAPSHOT", 8);

    assertThat(generated.get("generatorVersion").asText())
        .isNotEqualTo(frozen.get("generatorVersion").asText());
    assertThat(generated.get("sourceHash").asText())
        .matches("[0-9a-f]{64}")
        .isNotEqualTo(frozen.get("sourceHash").asText());

    ObjectNode f = frozen.deepCopy();
    ObjectNode g = generated.deepCopy();
    for (String k : new String[] {"generatorVersion", "sourceHash"}) {
      f.remove(k);
      g.remove(k);
    }
    assertThat(g).isEqualTo(f);
  }

  @Test
  void hashIsStable() {
    ValidationSchemaExporter e = new ValidationSchemaExporter();
    assertThat(e.export("a", 8).get("sourceHash")).isEqualTo(e.export("b", 8).get("sourceHash"));
    assertThat(e.export("a", 8).get("sourceHash"))
        .isNotEqualTo(e.export("a", 10).get("sourceHash"));
  }
}
