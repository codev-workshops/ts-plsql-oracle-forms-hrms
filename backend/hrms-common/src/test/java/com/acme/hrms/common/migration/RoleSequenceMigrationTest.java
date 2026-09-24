package com.acme.hrms.common.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runtime companion of {@link RoleSequenceContractTest}: V12 advances the V2 {@code seq_role} on
 * PostgreSQL so the first {@code nextval} is {@code >= 1000}, above any existing role id and above
 * the sequence's prior position – for a fresh database, an already advanced sequence and a database
 * that already holds custom roles allocated below 1000.
 */
class RoleSequenceMigrationTest {

  @AfterAll
  static void restoreLatestSchema() {
    HrmsPostgres.resetSchema();
  }

  @Test
  void freshDatabaseStartsCustomRolesAt1000() {
    Flyway flyway = HrmsPostgres.flyway();
    flyway.clean();
    flyway.migrate();
    JdbcTemplate jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    assertThat(sequences(jdbc)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select nextval('seq_role')", Long.class)).isEqualTo(1000L);
    assertThat(jdbc.queryForObject("select nextval('seq_role')", Long.class)).isEqualTo(1001L);
  }

  @Test
  void preAdvancedSequenceIsNeverMovedBackwards() {
    Flyway flyway = HrmsPostgres.flyway();
    flyway.clean();
    Flyway.configure().configuration(flyway.getConfiguration()).target("11").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    jdbc.queryForObject("select setval('seq_role', 5000, true)", Long.class);

    flyway.migrate();

    assertThat(latestVersion(jdbc)).isEqualTo(12);
    assertThat(sequences(jdbc)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select nextval('seq_role')", Long.class)).isEqualTo(5001L);
  }

  @Test
  void existingCustomRolesAboveTheFloorAreSkipped() {
    Flyway flyway = HrmsPostgres.flyway();
    flyway.clean();
    Flyway.configure().configuration(flyway.getConfiguration()).target("11").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    // roles created before V12 with hand-picked ids (V2-era sequence still at 1)
    jdbc.update(
        "insert into roles (role_id, role_code, role_name, min_grade, max_grade, created_by)"
            + " values (4, 'LEGACY_A', 'Legacy A', 1, 10, 't'),"
            + " (1200, 'LEGACY_B', 'Legacy B', 1, 10, 't')");
    // a V11-era allocation would collide with the seeded rows
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into roles (role_id, role_code, role_name, min_grade, max_grade,"
                        + " created_by) values (nextval('seq_role'), 'C', 'C', 1, 10, 't')"))
        .isInstanceOf(DuplicateKeyException.class);

    flyway.migrate();

    assertThat(jdbc.queryForObject("select nextval('seq_role')", Long.class)).isEqualTo(1201L);
    jdbc.update(
        "insert into roles (role_id, role_code, role_name, min_grade, max_grade, created_by)"
            + " values (nextval('seq_role'), 'NEW', 'New', 1, 10, 't')");
    assertThat(jdbc.queryForObject("select role_id from roles where role_code = 'NEW'", Long.class))
        .isEqualTo(1202L);
  }

  @Test
  void v12AddsTheActiveHolidayUniqueIndexWithoutTouchingInactiveRows() {
    Flyway flyway = HrmsPostgres.flyway();
    flyway.clean();
    Flyway.configure().configuration(flyway.getConfiguration()).target("11").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    // an inactive duplicate pre-exists: the partial index must still build
    jdbc.update(
        "insert into holidays (holiday_id, holiday_name, holiday_date, location_code, active_flag,"
            + " created_by, created_date) values (1, 'a', date '2030-01-01', null, 'Y', 't', now()),"
            + " (2, 'b', date '2030-01-01', null, 'N', 't', now())");

    flyway.migrate();

    assertThat(
            jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'uk_holidays_active_date_loc'",
                String.class))
        .contains("UNIQUE")
        .contains("active_flag")
        .contains("COALESCE");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into holidays (holiday_id, holiday_name, holiday_date, location_code,"
                        + " active_flag, created_by, created_date)"
                        + " values (3, 'c', date '2030-01-01', null, 'Y', 't', now())"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  private static int sequences(JdbcTemplate jdbc) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from pg_class where relkind = 'S' and relname = 'seq_role'",
            Integer.class);
    return n == null ? -1 : n;
  }

  private static int latestVersion(JdbcTemplate jdbc) {
    Integer v =
        jdbc.queryForObject(
            "select max(version::int) from flyway_schema_history where success", Integer.class);
    return v == null ? -1 : v;
  }
}
