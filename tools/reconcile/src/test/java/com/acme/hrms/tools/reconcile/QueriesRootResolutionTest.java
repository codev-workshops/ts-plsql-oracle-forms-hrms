package com.acme.hrms.tools.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The default query pack is found from the repo root, from tools/reconcile and from the jar. */
class QueriesRootResolutionTest {

  @Test
  void resolvesFromRepoRootAndFromModuleDirectory() {
    Path repo = HrmsPostgres.repoRoot();
    Path expected = repo.resolve(ReconcileMain.DEFAULT_QUERIES);
    assertThat(ReconcileMain.resolveQueries(repo, null)).isEqualTo(expected);
    assertThat(ReconcileMain.resolveQueries(repo.resolve("tools/reconcile"), null))
        .isEqualTo(expected);
    assertThat(ReconcileMain.resolveQueries(repo.resolve("tools/reconcile/target"), null))
        .isEqualTo(expected);
  }

  @Test
  void fallsBackToJarLocationWhenCwdIsElsewhere(@TempDir Path elsewhere) {
    Path repo = HrmsPostgres.repoRoot();
    assertThat(ReconcileMain.resolveQueries(elsewhere, repo.resolve("tools/reconcile/target")))
        .isEqualTo(repo.resolve(ReconcileMain.DEFAULT_QUERIES));
  }

  @Test
  void explicitQueriesFlagWinsAndMissingPackIsAnError(@TempDir Path elsewhere) throws Exception {
    Path custom = elsewhere.resolve("custom");
    Files.createDirectories(custom.resolve("pg"));
    assertThat(ReconcileMain.queriesRoot(custom.toString())).isEqualTo(custom);
    assertThatThrownBy(() -> ReconcileMain.resolveQueries(elsewhere, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--queries");
  }
}
