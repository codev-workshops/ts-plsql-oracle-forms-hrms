package com.acme.hrms.tools.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CUTOVER_PLAN.md §5.3 / §5.4 – PostgreSQL leg of the performance-group data migration: keyed
 * upsert of the three tables in FK order, sequences restarted at MAX+1, reverse-extract MERGE
 * shape. The Oracle legs (AS OF SCN extract, MERGE apply) are untested-live: no Oracle here.
 */
class PerformanceCutoverTest {

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void seed() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
  }

  @Test
  void groupIsTheThreeOwnedTablesInForeignKeyOrder() {
    assertThat(TableGroup.byFlag("performance").tables())
        .containsExactly("review_cycles", "performance_reviews", "performance_goals");
    for (String t : TableGroup.PERFORMANCE.tables()) {
      assertThat(TableGroup.SEQUENCES).containsKey(t);
      Integer n =
          jdbc.queryForObject(
              "select count(*) from pg_class where relkind = 'S' and relname = ?",
              Integer.class,
              TableGroup.SEQUENCES.get(t));
      assertThat(n).as(t).isEqualTo(1);
    }
  }

  @Test
  void bulkUpsertIsIdempotentAndRestartsSequencesAtMaxPlusOne() throws Exception {
    List<String> cols =
        List.of(
            "cycle_id",
            "cycle_name",
            "cycle_year",
            "start_date",
            "end_date",
            "status",
            "created_by");
    String sql = BulkLoader.upsertSql("review_cycles", cols);
    Object[] row = {
      777_000L,
      "Oracle-loaded",
      2030,
      java.sql.Date.valueOf("2030-01-01"),
      java.sql.Date.valueOf("2030-12-31"),
      "DRAFT",
      "CDC"
    };
    jdbc.update(sql, row);
    jdbc.update(sql, row);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from review_cycles where cycle_id = 777000", Integer.class))
        .isEqualTo(1);

    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      assertThat(BulkLoader.restartSequence(c, "review_cycles")).isEqualTo(777_001L);
      assertThat(BulkLoader.restartSequence(c, "performance_reviews")).isEqualTo(5005L);
      assertThat(BulkLoader.restartSequence(c, "performance_goals"))
          .isEqualTo(
              jdbc.queryForObject(
                  "select coalesce(max(goal_id), 0) + 1 from performance_goals", Long.class));
    }
    assertThat(jdbc.queryForObject("select nextval('seq_review_cycle')", Long.class))
        .isEqualTo(777_001L);
    assertThat(jdbc.queryForObject("select nextval('seq_perf_review')", Long.class))
        .isEqualTo(5005L);
    jdbc.update("delete from review_cycles where cycle_id = 777000");
  }

  @Test
  void checksumGateCoversEveryPerformanceTable() throws Exception {
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      for (String t : TableGroup.PERFORMANCE.tables()) {
        Checksum.TableChecksum a = Checksum.compute(c, t, false);
        assertThat(a.rows())
            .as(t)
            .isEqualTo(jdbc.queryForObject("select count(*) from " + t, Long.class));
        assertThat(a).isEqualTo(Checksum.compute(c, t, false));
      }
    }
  }

  @Test
  void reverseExtractMergesOnPrimaryKeyForRollback() {
    assertThat(
            ReverseExtract.mergeSql(
                "performance_reviews", List.of("REVIEW_ID", "STATUS", "OVERALL_RATING")))
        .isEqualTo(
            "merge into PERFORMANCE_REVIEWS t using (select ? as REVIEW_ID, ? as STATUS,"
                + " ? as OVERALL_RATING from dual) s on (t.REVIEW_ID = s.REVIEW_ID)"
                + " when matched then update set t.STATUS = s.STATUS, t.OVERALL_RATING = s.OVERALL_RATING"
                + " when not matched then insert (REVIEW_ID, STATUS, OVERALL_RATING)"
                + " values (s.REVIEW_ID, s.STATUS, s.OVERALL_RATING)");
    assertThat(ReverseExtract.mergeSql("performance_goals", List.of("GOAL_ID", "PROGRESS_PCT")))
        .startsWith("merge into PERFORMANCE_GOALS t ")
        .contains("on (t.GOAL_ID = s.GOAL_ID)");
    assertThat(ReverseExtract.mergeSql("review_cycles", List.of("CYCLE_ID", "STATUS")))
        .contains("on (t.CYCLE_ID = s.CYCLE_ID)");
  }
}
