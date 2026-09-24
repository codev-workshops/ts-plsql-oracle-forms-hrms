package com.acme.hrms.common.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * P5 contract pin for {@code roles.role_id} allocation (contracts/p5-reporting-decommission): V2
 * already owns {@code seq_role}, so the forward V12 migration must not create it again but must
 * advance it so that {@code nextval('seq_role')} is at least 1000 and strictly greater than both
 * {@code max(roles.role_id)} and the sequence's prior value, whatever rows an upgraded database
 * already holds. Text-level so it holds before and after the backend child adds V12.
 */
class RoleSequenceContractTest {

  private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");
  private static final Pattern V2_CREATE =
      Pattern.compile("(?im)^\\s*create\\s+sequence\\s+seq_role\\s+start\\s+with\\s+1\\b");
  private static final Pattern CREATE_SEQ_ROLE =
      Pattern.compile("(?i)create\\s+sequence\\s+(if\\s+not\\s+exists\\s+)?seq_role\\b");
  private static final Pattern RESTART_FIXED =
      Pattern.compile("(?i)alter\\s+sequence\\s+seq_role\\s+restart");
  private static final Pattern SETVAL =
      Pattern.compile(
          "(?is)setval\\(\\s*'seq_role'\\s*,\\s*greatest\\(.*?1000.*?\\)\\s*,\\s*false\\s*\\)");
  private static final Pattern MAX_ROLE_ID = Pattern.compile("(?is)max\\(\\s*role_id\\s*\\)");
  private static final Pattern LAST_VALUE =
      Pattern.compile("(?i)last_value|nextval\\('seq_role'\\)");

  @Test
  void v2CreatesSeqRoleStartingAtOneAndSeedsRolesOneToThreeWithoutIt() {
    String v2 = read(MIGRATIONS.resolve("V2__p0_auth_foundation.sql"));
    assertThat(v2).containsPattern(V2_CREATE);
    assertThat(v2).doesNotContain("nextval('seq_role')");
    assertThat(v2)
        .containsPattern("(?i)insert\\s+into\\s+roles\\s*\\(role_id,")
        .containsPattern("\\(1,\\s*'")
        .containsPattern("\\(2,\\s*'")
        .containsPattern("\\(3,\\s*'");
  }

  @Test
  void onlyV2CreatesSeqRole() {
    List<Path> creators =
        migrations().filter(p -> CREATE_SEQ_ROLE.matcher(read(p)).find()).toList();
    assertThat(creators)
        .extracting(p -> p.getFileName().toString())
        .containsExactly("V2__p0_auth_foundation.sql");
  }

  @Test
  void v12WhenPresentAdvancesSeqRoleRelativeToExistingRowsAndSequence() {
    List<Path> v12 =
        migrations().filter(p -> p.getFileName().toString().startsWith("V12__")).toList();
    assertThat(v12).hasSizeLessThanOrEqualTo(1);
    if (v12.isEmpty()) {
      return; // contract-only state: backend child adds V12__p5_admin_expansion.sql
    }
    String sql = read(v12.get(0));
    assertThat(sql).doesNotContainPattern(CREATE_SEQ_ROLE);
    assertThat(sql).doesNotContainPattern(RESTART_FIXED);
    assertThat(sql).containsPattern(SETVAL);
    assertThat(sql).containsPattern(MAX_ROLE_ID);
    assertThat(sql).containsPattern(LAST_VALUE);
  }

  private static Stream<Path> migrations() {
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".sql"))
          .sorted()
          .toList()
          .stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
