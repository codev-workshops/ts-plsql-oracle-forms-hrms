package com.acme.hrms.tools.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Phase 5 decommission scripts (CUTOVER_PLAN.md §9.2) are {@code untested-live}: there is no
 * Oracle, Kafka Connect or Data Pump here, so this test pins what can be verified offline – the
 * scripts parse, refuse to run without connection settings, and in {@code DRY_RUN=true} walk every
 * step in the documented order without touching anything, writing the evidence directory the
 * runbook (docs/DECOMMISSION_RUNBOOK.md) archives.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class CdcDecommissionScriptsTest {

  private static Path scripts() {
    Path p = Path.of("scripts");
    return Files.isDirectory(p) ? p : Path.of("tools/cdc-sync/scripts");
  }

  @Test
  void scriptsParseAndAreExecutable() throws Exception {
    for (String s : List.of("cdc-shutdown.sh", "final-oracle-extract.sh")) {
      Path script = scripts().resolve(s);
      assertThat(Files.isExecutable(script)).as(s).isTrue();
      assertThat(run(List.of("bash", "-n", script.toString()), null).exit).as(s).isZero();
    }
  }

  @Test
  void scriptsRefuseToRunWithoutConnectionSettings() throws Exception {
    Result r = run(List.of("bash", scripts().resolve("cdc-shutdown.sh").toString()), null);
    assertThat(r.exit).isNotZero();
    assertThat(r.output).contains("ORACLE_URL");
  }

  @Test
  void dryRunWalksEveryStepInOrderAndWritesTheEvidenceDirectory() throws Exception {
    Path out = Files.createTempDirectory("decommission-evidence");
    Result shutdown = run(List.of("bash", scripts().resolve("cdc-shutdown.sh").toString()), out);
    assertThat(shutdown.exit).as(shutdown.output).isZero();
    assertThat(shutdown.output)
        .containsSubsequence("1/4 drain", "2/4 gate", "3/4 stop", "4/4 record")
        .containsSubsequence(
            "DRY-RUN: java -jar", "checksum --group reference",
            "DRY-RUN: curl -fsS -X DELETE", "hrms-postgres-sink",
            "DRY-RUN: curl -fsS -X DELETE", "hrms-oracle-source")
        .doesNotContain("secret-pwd");
    assertThat(out.resolve("final-scn.txt")).hasContent("DRY-RUN");

    Result extract =
        run(List.of("bash", scripts().resolve("final-oracle-extract.sh").toString()), out);
    assertThat(extract.exit).as(extract.output).isZero();
    assertThat(extract.output)
        .containsSubsequence(
            "a. Data Pump", "b. last output", "c. checksum", "d. legacy object", "e. manifest")
        .contains("expdp", "capture", "--as-of", "compare", "--baseline");
    for (TableGroup g : TableGroup.values()) {
      assertThat(extract.output).as(g.flag()).contains("checksum --group " + g.flag());
    }
    assertThat(out.resolve("MANIFEST.sha256")).exists();
    assertThat(Files.readString(out.resolve("MANIFEST.sha256")))
        .contains("final-scn.txt")
        .contains("final-extract.log");
  }

  private record Result(int exit, String output) {}

  private static Result run(List<String> cmd, Path outDir)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment().remove("ORACLE_URL");
    if (outDir != null) {
      pb.environment().put("DRY_RUN", "true");
      pb.environment().put("OUT_DIR", outDir.toString());
      pb.environment().put("ORACLE_URL", "jdbc:oracle:thin:@//oracle:1521/HRMSPDB");
      pb.environment().put("ORACLE_USER", "hrms");
      pb.environment().put("ORACLE_PASSWORD", "secret-pwd");
      pb.environment().put("PG_URL", "jdbc:postgresql://pg:5432/hrms");
      pb.environment().put("PG_USER", "hrms");
      pb.environment().put("PG_PASSWORD", "secret-pwd");
    }
    Process p = pb.start();
    String output = new String(p.getInputStream().readAllBytes());
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
    return new Result(p.exitValue(), output);
  }
}
