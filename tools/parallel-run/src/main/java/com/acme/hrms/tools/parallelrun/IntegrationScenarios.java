package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.LegacyCall;
import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import java.util.List;
import java.util.Map;

/**
 * Phase 5 integration-feed scenarios (TEST_STRATEGY.md §5 row 5, COMPONENT_MAPPING.md §9): the GL
 * journal and benefits feed produced by the target for the frozen seed are compared with the {@code
 * UTL_FILE} output of {@code PKG_INTEGRATION.generate_gl_journal / export_benefits_feed}.
 *
 * <p>Legacy leg: the procedure writes to the {@code GL_FEED_OUT} / {@code BENEFITS_FEED_OUT}
 * directory objects; the block re-reads the file with {@code UTL_FILE}, counts the records and
 * hashes the bytes with {@code DBMS_CRYPTO.HASH(..., HASH_SH256)} so both legs project {@code
 * recordCount} and {@code sha256}. Recorded expectations are the committed golden files {@code
 * tests/golden/feeds/gl_journal_9001.txt} / {@code benefits_20240630.txt} (provenance in
 * tests/golden/README.md; no Oracle instance was available, {@code legacy_source=recorded}).
 *
 * <p>The feeds read the seed population and run 9001 (APPROVED, MAY-2024), so they run before
 * {@code salary.*}, {@code payroll.*} and {@code employee.*} change either.
 */
final class IntegrationScenarios {

  private IntegrationScenarios() {}

  static final String MODULE = "integration";

  /** emp 1, EXECUTIVE (PAYROLL:APPROVE, ADMIN:EDIT, EMPLOYEE:VIEW). */
  private static final String EXEC = PerformanceScenarios.ADMIN;

  /** Seed APPROVED run (tools/fixtures/pg/03_transaction_data.sql). */
  static final int APPROVED_RUN = 9001;

  /** Seed CALCULATED run: GL export refused with -20701. */
  static final int CALCULATED_RUN = 9002;

  static final String BENEFITS_EFFECTIVE = "2024-06-30";

  /** sha256 of tests/golden/feeds/gl_journal_9001.txt (16 D records + H + T). */
  static final String GL_SHA256 =
      "16e720b8a0be844a0192a993c293c6ebe15640c67e96fa0869c98bf91ff3c52a";

  /** sha256 of tests/golden/feeds/benefits_20240630.txt (23 fixed-width E records). */
  static final String BENEFITS_SHA256 =
      "698fe9ceae59411e5e5aa4f18cb82ab27a23ff97f804f4673a7e641cf9b33c40";

  static List<Scenario> all() {
    return List.of(
        new Scenario(
            "integration.gl-feed.approved-run",
            MODULE,
            utlFile(
                "pkg_integration.generate_gl_journal(" + APPROVED_RUN + ", :user)",
                "GL_FEED_OUT",
                "GL_JOURNAL_" + APPROVED_RUN + "_' || to_char(sysdate, 'YYYYMMDD') || '.txt",
                // record count excludes the H and T lines, as the target's recordCount does
                "-2"),
            post("/api/integration/gl-feed", Map.of("runId", APPROVED_RUN), EXEC),
            Outcome.ok(
                Map.of(
                    "feed", "GL_JOURNAL",
                    "status", "SUCCESS",
                    "recordCount", "16",
                    "sha256", GL_SHA256))),
        new Scenario(
            "integration.gl-feed.run-not-approved",
            MODULE,
            // legacy writes the file regardless of status (no RAISE): legacy_source=none
            null,
            post("/api/integration/gl-feed", Map.of("runId", CALCULATED_RUN), EXEC),
            Outcome.error("-20701")),
        new Scenario(
            "integration.gl-feed.run-not-found",
            MODULE,
            plsql("begin pkg_integration.generate_gl_journal(4242, :user); end;", List.of()),
            post("/api/integration/gl-feed", Map.of("runId", 4242), EXEC),
            // legacy: NO_DATA_FOUND from the SELECT ... INTO; contract 404 code
            Outcome.error("RUN_NOT_FOUND")),
        new Scenario(
            "integration.benefits-feed.seed",
            MODULE,
            utlFile(
                "pkg_integration.export_benefits_feed(date '" + BENEFITS_EFFECTIVE + "', :user)",
                "BENEFITS_FEED_OUT",
                "BENEFITS_' || to_char(date '" + BENEFITS_EFFECTIVE + "', 'YYYYMMDD') || '.txt",
                "0"),
            post(
                "/api/integration/benefits-feed",
                Map.of("effectiveDate", BENEFITS_EFFECTIVE),
                EXEC),
            Outcome.ok(
                Map.of(
                    "feed", "BENEFITS_FEED",
                    "status", "SUCCESS",
                    "recordCount", "23",
                    "sha256", BENEFITS_SHA256))),
        new Scenario(
            "integration.status.after-feeds",
            MODULE,
            // legacy keeps the flag in SYSTEM_PARAMETERS (INTEGRATION / GL_JOURNAL_STATUS)
            plsql(
                "begin :feed := 'GL_JOURNAL';"
                    + " :status := pkg_integration.get_integration_status('GL_JOURNAL'); end;",
                List.of("feed", "status")),
            // list ordered GL_JOURNAL, BENEFITS_FEED, TIME_ATTENDANCE: first element projected
            get("/api/integration/status", EXEC),
            Outcome.ok(Map.of("feed", "GL_JOURNAL", "status", "SUCCESS"))));
  }

  /** Legacy expectations where the contract deliberately diverges (error-codes.md). */
  static Outcome legacyOutcome(Scenario s) {
    return switch (s.id()) {
      case "integration.gl-feed.run-not-found" -> Outcome.error("ORA-01403");
      default -> s.expect();
    };
  }

  /**
   * Runs {@code procedureCall}, re-reads the produced file from {@code directory} and binds {@code
   * feed}/{@code status}/{@code recordCount}/{@code sha256} exactly as the target's {@code
   * IntegrationFile} reports them ({@code feed} = the directory's feed name; legacy has no per-run
   * status row, so reaching the end of the block without an exception is {@code SUCCESS}). {@code
   * countAdjust} removes header/trailer lines from the record count.
   */
  private static LegacyCall utlFile(
      String procedureCall, String directory, String fileNameExpr, String countAdjust) {
    String feed = directory.startsWith("GL") ? "GL_JOURNAL" : "BENEFITS_FEED";
    return new LegacyCall(
        "declare f utl_file.file_type; l varchar2(4000); n number := 0; raw_all blob;"
            + " begin "
            + procedureCall
            + "; dbms_lob.createtemporary(raw_all, true);"
            + " f := utl_file.fopen('"
            + directory
            + "', '"
            + fileNameExpr
            + ", 'r', 32767);"
            + " loop begin utl_file.get_line(f, l); exception when no_data_found then exit; end;"
            + " n := n + 1; dbms_lob.append(raw_all, utl_raw.cast_to_raw(l || chr(10))); end loop;"
            + " utl_file.fclose(f);"
            + " :feed := '"
            + feed
            + "'; :recordCount := to_char(n "
            + (countAdjust.startsWith("-") ? countAdjust : "+ " + countAdjust)
            + "); :sha256 := lower(rawtohex(dbms_crypto.hash(raw_all, 4)));"
            + " :status := 'SUCCESS'; end;",
        List.of("feed", "status", "recordCount", "sha256"));
  }

  private static LegacyCall plsql(String block, List<String> outBinds) {
    return new LegacyCall(block, outBinds);
  }

  private static RestCall post(String path, Map<String, Object> body, String auth) {
    return new RestCall("POST", path, body, auth, false, List.of());
  }

  private static RestCall get(String path, String auth) {
    return new RestCall("GET", path, null, auth, false, List.of());
  }
}
