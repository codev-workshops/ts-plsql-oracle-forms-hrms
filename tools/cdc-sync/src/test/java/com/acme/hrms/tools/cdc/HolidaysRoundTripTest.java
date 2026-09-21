package com.acme.hrms.tools.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CUTOVER_PLAN.md §4.2 item 0.5b "round-trip smoke test on HOLIDAYS" – PostgreSQL leg. The Oracle
 * leg (MERGE) is executed by the integration session.
 */
class HolidaysRoundTripTest {

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void seed() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
  }

  @Test
  void checksumIsStableAndChangesWhenARowChanges() throws Exception {
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      Checksum.TableChecksum a = Checksum.compute(c, "holidays", false);
      Checksum.TableChecksum b = Checksum.compute(c, "holidays", false);
      assertThat(a.rows()).isEqualTo(10);
      assertThat(a).isEqualTo(b);
      jdbc.update("update holidays set holiday_name = holiday_name || '!' where holiday_id = 1");
      Checksum.TableChecksum changed = Checksum.compute(c, "holidays", false);
      assertThat(changed.columns().get("holiday_name"))
          .isNotEqualTo(a.columns().get("holiday_name"));
      assertThat(changed.columns().get("holiday_date")).isEqualTo(a.columns().get("holiday_date"));
      jdbc.update(
          "update holidays set holiday_name = rtrim(holiday_name, '!') where holiday_id = 1");
      assertThat(Checksum.compute(c, "holidays", false)).isEqualTo(a);
    }
  }

  @Test
  void upsertIsIdempotentOnPostgres() throws Exception {
    List<String> cols = List.of("holiday_id", "holiday_name", "holiday_date", "created_by");
    String sql = BulkLoader.upsertSql("holidays", cols);
    assertThat(sql)
        .isEqualTo(
            "insert into holidays (holiday_id, holiday_name, holiday_date, created_by) values (?, ?, ?, ?)"
                + " on conflict (holiday_id) do update set holiday_name = excluded.holiday_name,"
                + " holiday_date = excluded.holiday_date, created_by = excluded.created_by");
    Object[] row = {1, "New Year's Day", java.sql.Date.valueOf("2024-01-01"), "CDC"};
    jdbc.update(sql, row);
    jdbc.update(sql, row);
    assertThat(jdbc.queryForObject("select count(*) from holidays", Integer.class)).isEqualTo(10);
  }

  @Test
  void reverseMergeSqlTargetsOracleUpperCaseIdentifiers() {
    String sql = ReverseExtract.mergeSql("holidays", List.of("HOLIDAY_ID", "HOLIDAY_NAME"));
    assertThat(sql)
        .isEqualTo(
            "merge into HOLIDAYS t using (select ? as HOLIDAY_ID, ? as HOLIDAY_NAME from dual) s"
                + " on (t.HOLIDAY_ID = s.HOLIDAY_ID) when matched then update set"
                + " t.HOLIDAY_NAME = s.HOLIDAY_NAME when not matched then insert (HOLIDAY_ID,"
                + " HOLIDAY_NAME) values (s.HOLIDAY_ID, s.HOLIDAY_NAME)");
  }

  @Test
  void everyTableInAGroupHasAPrimaryKeyAndExistsInTheSchema() {
    for (TableGroup g : TableGroup.values()) {
      for (String t : g.tables()) {
        assertThat(TableGroup.PRIMARY_KEYS).as(t).containsKey(t);
        Integer n =
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = ? and column_name = ?",
                Integer.class,
                t,
                TableGroup.PRIMARY_KEYS.get(t));
        assertThat(n).as(t + "." + TableGroup.PRIMARY_KEYS.get(t)).isEqualTo(1);
      }
    }
  }

  @Test
  void typeMappingInvertsEmptyStringsAndBooleans() {
    assertThat(TypeMapping.toOracle("")).isNull();
    assertThat(TypeMapping.toOracle(Boolean.TRUE)).isEqualTo("Y");
    assertThat(TypeMapping.toPostgres(new java.math.BigDecimal("42"), java.sql.Types.NUMERIC))
        .isEqualTo(42L);
    assertThat(TypeMapping.toPostgres(new java.math.BigDecimal("42.50"), java.sql.Types.NUMERIC))
        .isEqualTo(new java.math.BigDecimal("42.50"));
    assertThat(TypeMapping.toPostgres("Y ", java.sql.Types.CHAR)).isEqualTo("Y");
  }
}
