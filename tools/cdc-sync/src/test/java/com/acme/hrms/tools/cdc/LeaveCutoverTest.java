package com.acme.hrms.tools.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CUTOVER_PLAN.md §6.3 – PostgreSQL leg of the leave-group data migration: the three owned tables
 * in FK order (balances ← requests, accrual log), keyed upsert that skips the generated {@code
 * available} column, sequences restarted at MAX+1, reverse-extract MERGE that never writes the
 * Oracle virtual column. The Oracle legs (AS OF SCN extract, MERGE apply) are untested-live: no
 * Oracle here.
 */
class LeaveCutoverTest {

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void seed() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
  }

  @Test
  void groupIsTheThreeOwnedTablesInForeignKeyOrder() {
    assertThat(TableGroup.byFlag("leave").tables())
        .containsExactly("leave_balances", "leave_requests", "leave_accrual_log");
    for (String t : TableGroup.LEAVE.tables()) {
      assertThat(TableGroup.SEQUENCES).containsKey(t);
      Integer n =
          jdbc.queryForObject(
              "select count(*) from pg_class where relkind = 'S' and relname = ?",
              Integer.class,
              TableGroup.SEQUENCES.get(t));
      assertThat(n).as(t).isEqualTo(1);
    }
    // reference tables the group depends on are loaded by the REFERENCE group first
    assertThat(TableGroup.REFERENCE.tables()).contains("leave_types", "holidays");
  }

  @Test
  void generatedColumnRegistryMatchesTheSchema() {
    List<String> generated =
        jdbc.queryForList(
            "select table_name || '.' || column_name from information_schema.columns"
                + " where table_schema = current_schema() and is_generated = 'ALWAYS'",
            String.class);
    assertThat(generated).containsExactly("leave_balances.available");
    assertThat(TableGroup.isGenerated("leave_balances", "AVAILABLE")).isTrue();
    assertThat(TableGroup.isGenerated("leave_balances", "pending")).isFalse();
    assertThat(TableGroup.isGenerated("leave_requests", "available")).isFalse();
  }

  @Test
  void bulkUpsertSkipsTheStoredAvailableColumnAndRestartsSequences() throws Exception {
    try (Connection c = HrmsPostgres.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement("select * from leave_balances where 1 = 0");
        ResultSet rs = ps.executeQuery()) {
      List<Integer> idx = BulkLoader.writableColumns("leave_balances", rs.getMetaData());
      List<String> cols = new java.util.ArrayList<>();
      for (int i : idx) {
        cols.add(rs.getMetaData().getColumnLabel(i));
      }
      assertThat(cols).doesNotContain("available").contains("balance_id", "pending", "used");
      assertThat(idx).hasSize(rs.getMetaData().getColumnCount() - 1);
    }

    List<String> cols =
        List.of(
            "balance_id",
            "emp_id",
            "leave_type_id",
            "calendar_year",
            "opening_balance",
            "accrued",
            "used",
            "adjustment",
            "pending",
            "carryover_from_prev",
            "created_by");
    String sql = BulkLoader.upsertSql("leave_balances", cols);
    assertThat(sql).doesNotContain("available").contains("on conflict (balance_id)");
    Object[] row = {777_000L, 2, 1, 2031, 10, 2.5, 3, 0, 1, 0, "CDC"};
    jdbc.update(sql, row);
    jdbc.update(sql, row);
    assertThat(
            jdbc.queryForObject(
                "select available from leave_balances where balance_id = 777000", BigDecimal.class))
        .isEqualByComparingTo("8.5"); // 10 + 2.5 - 3 + 0 - 1, computed by PostgreSQL

    // UK_LEAVE_BAL is preserved across the load: same (emp, type, year) under another id fails
    Object[] dup = {777_001L, 2, 1, 2031, 10, 0, 0, 0, 0, 0, "CDC"};
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, dup))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      assertThat(BulkLoader.restartSequence(c, "leave_balances")).isEqualTo(777_001L);
      assertThat(BulkLoader.restartSequence(c, "leave_requests"))
          .isEqualTo(
              jdbc.queryForObject(
                  "select coalesce(max(request_id), 0) + 1 from leave_requests", Long.class));
      assertThat(BulkLoader.restartSequence(c, "leave_accrual_log"))
          .isEqualTo(
              jdbc.queryForObject(
                  "select coalesce(max(accrual_id), 0) + 1 from leave_accrual_log", Long.class));
    }
    assertThat(jdbc.queryForObject("select nextval('seq_leave_balance')", Long.class))
        .isEqualTo(777_001L);
    jdbc.update("delete from leave_balances where balance_id = 777000");
  }

  @Test
  void checksumGateCoversEveryLeaveTable() throws Exception {
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      for (String t : TableGroup.LEAVE.tables()) {
        Checksum.TableChecksum a = Checksum.compute(c, t, false);
        assertThat(a.rows())
            .as(t)
            .isEqualTo(jdbc.queryForObject("select count(*) from " + t, Long.class));
        assertThat(a).isEqualTo(Checksum.compute(c, t, false));
      }
    }
  }

  @Test
  void reverseExtractMergesOnPrimaryKeyWithoutTheVirtualColumn() {
    assertThat(
            ReverseExtract.mergeSql(
                "leave_balances", List.of("BALANCE_ID", "USED", "PENDING", "ADJUSTMENT")))
        .isEqualTo(
            "merge into LEAVE_BALANCES t using (select ? as BALANCE_ID, ? as USED,"
                + " ? as PENDING, ? as ADJUSTMENT from dual) s on (t.BALANCE_ID = s.BALANCE_ID)"
                + " when matched then update set t.USED = s.USED, t.PENDING = s.PENDING,"
                + " t.ADJUSTMENT = s.ADJUSTMENT"
                + " when not matched then insert (BALANCE_ID, USED, PENDING, ADJUSTMENT)"
                + " values (s.BALANCE_ID, s.USED, s.PENDING, s.ADJUSTMENT)");
    assertThat(ReverseExtract.mergeSql("leave_requests", List.of("REQUEST_ID", "STATUS")))
        .startsWith("merge into LEAVE_REQUESTS t ")
        .contains("on (t.REQUEST_ID = s.REQUEST_ID)");
    assertThat(
            ReverseExtract.mergeSql(
                "leave_accrual_log", List.of("ACCRUAL_ID", "ACCRUAL_TYPE", "ACCRUAL_AMOUNT")))
        .contains("on (t.ACCRUAL_ID = s.ACCRUAL_ID)");
  }

  @Test
  void accrualLogIdempotencyKeySurvivesTheCutoverMigration() {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from pg_indexes where indexname = 'uk_leave_accrual_idem'",
            Integer.class);
    assertThat(n).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name ="
                    + " 'leave_accrual_log' and column_name = 'accrual_type'",
                Integer.class))
        .isEqualTo(1);
  }
}
