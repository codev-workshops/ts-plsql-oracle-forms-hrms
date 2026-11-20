package com.hrms.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.auth.MutableClock;
import com.hrms.auth.PasswordResetService;
import com.hrms.leave.LeaveAccrualService;
import com.hrms.leave.LeaveCarryoverService;
import com.hrms.schema.EmbeddedPostgres16;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P8 data-migration rehearsal: the repository seed ({@code data/seed}, ported to {@code R__dev_seed.sql}) plays
 * the legacy Oracle export, with known legacy defects added. The production schema (db/migration only) is
 * loaded through {@code backend/migration/*.sql}, then reconciled by row count and checksum.
 */
@SpringBootTest
@Import(DataMigrationRehearsalTest.Clock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DataMigrationRehearsalTest {

    private static final Path MIGRATION = Path.of("migration");
    private static final Path SEED = Path.of("src/main/resources/db/seed/R__dev_seed.sql");
    private static final Pattern INSERT = Pattern.compile(
            "(?s)INSERT INTO hrms\\.(\\w+ \\(.*?\\)\\s*VALUES \\(.*?\\))\\s*ON CONFLICT \\([^)]*\\) DO NOTHING;");
    private static final Pattern UPDATE = Pattern.compile("UPDATE hrms\\.departments SET MANAGER_EMP_ID = (\\d+) "
            + "WHERE DEPT_ID = (\\d+);");

    @TestConfiguration
    static class Clock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPostgres16.newDatabaseUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration"); // production path: no seed
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    LeaveAccrualService accrual;

    @Autowired
    LeaveCarryoverService carryover;

    @Autowired
    PasswordResetService resets;

    @Test
    void legacyExportLoadsReconcilesAndIsSafeToOperateOn() throws Exception {
        clock.set(Instant.parse("2026-03-02T09:00:00Z"));
        jdbc.execute(Files.readString(MIGRATION.resolve("01_stage.sql")));
        jdbc.execute(Files.readString(MIGRATION.resolve("02_functions.sql")));
        int stagedEmployees = stageLegacyExport();
        addLegacyDefects();

        // Precheck stops the load on data the target constraints would reject
        assertThat(jdbc.queryForObject("SELECT legacy_stage.precheck()", Integer.class)).isEqualTo(2);
        assertThat(issues("BLOCKING")).containsExactlyInAnyOrder("DEF-24:leave_types", "OQ-06:employees");
        assertThat(issues("WARN")).contains("DEF-02:holidays", "DEF-04:employees", "OQ-P5-2:employees",
                "PKG-L04:leave_accrual_log", "V200:performance_goals");
        assertThat(issues("INFO")).contains("V200:performance_reviews", "FLAG_BACKFILL:holidays",
                "D-06:user_sessions");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT legacy_stage.load('America/New_York')", Long.class))
                .hasMessageContaining("BLOCKING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.employees", Integer.class)).isZero();

        // The data owners fix the blocking rows in the export (here: in the stage) and re-run
        jdbc.update("UPDATE legacy_stage.leave_types SET accrual_frequency = 'MONTHLY' WHERE leave_type_id = '6'");
        jdbc.update("UPDATE legacy_stage.employees SET email = 'priya.sharma2@company.com' WHERE emp_id = '36'");
        assertThat(jdbc.queryForObject("SELECT legacy_stage.precheck()", Integer.class)).isZero();
        long loaded = jdbc.queryForObject("SELECT legacy_stage.load('America/New_York')", Long.class);
        assertThat(loaded).isPositive();

        List<Map<String, Object>> report = jdbc.queryForList("SELECT * FROM legacy_stage.reconcile('America/New_York')");
        assertThat(report).hasSize(30).allSatisfy(r -> assertThat(r.get("status")).as(r.get("table_name").toString())
                .isIn("MATCH", "SKIPPED (V400)"));
        assertThat(report).filteredOn(r -> "performance_reviews".equals(r.get("table_name"))).singleElement()
                .satisfies(r -> {
                    assertThat(r.get("stage_rows")).isEqualTo(3L);
                    assertThat(r.get("target_rows")).isEqualTo(2L);
                });

        // Seed parity
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.employees", Integer.class)).isEqualTo(stagedEmployees);
        assertThat(jdbc.queryForObject("SELECT manager_emp_id FROM hrms.departments WHERE dept_id = 30", Long.class))
                .isEqualTo(30L);
        assertThat(jdbc.queryForObject("SELECT active_flag FROM hrms.employees WHERE emp_id = 99", Boolean.class))
                .isFalse(); // 'N' -> false
        assertThat(jdbc.queryForObject("SELECT floating_flag FROM hrms.holidays WHERE holiday_id = 1", Boolean.class))
                .isFalse(); // NULL backfilled (D-01)
        // V200 dedupe: the reviewed duplicate is kept and the goal follows it
        assertThat(jdbc.queryForList("SELECT review_id FROM hrms.performance_reviews ORDER BY review_id", Long.class))
                .containsExactly(501L, 503L);
        assertThat(jdbc.queryForMap("SELECT review_id, progress_pct FROM hrms.performance_goals WHERE goal_id = 900"))
                .containsEntry("review_id", 501L).containsEntry("progress_pct", new java.math.BigDecimal("100.00"));
        // OQ-P5-2: no legacy ciphertext in the application schema
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.employees WHERE ssn_encrypted IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legacy_stage.ssn_holding", Integer.class)).isEqualTo(1);
        // Oracle DATE (no zone) read in the legacy time zone
        assertThat(jdbc.queryForObject("SELECT login_time = TIMESTAMPTZ '2025-12-01 13:00:00Z' AND "
                + "session_status = 'CLOSED' FROM hrms.user_sessions WHERE session_id = 1", Boolean.class)).isTrue();
        // D-02: identities and the EMP- sequence continue above the migrated ids
        assertThat(jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('hrms.employees', 'emp_id'))",
                Long.class)).isEqualTo(10000L);
        assertThat(jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('hrms.holidays', 'holiday_id'))",
                Long.class)).isGreaterThan(10L);
        assertThat(jdbc.queryForObject("SELECT nextval('hrms.seq_emp_number')", Long.class)).isEqualTo(1000L);

        // Legacy job history is honoured: no month is accrued twice, no year carried over twice
        assertThat(accrual.run(LocalDate.of(2026, 2, 1), "system:leave-accrual").created()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.leave_accrual_log WHERE run_id IS NULL",
                Integer.class)).isEqualTo(1); // the legacy double accrual stays unlinked
        assertThat(carryover.carryOver(2025, "system:leave-carryover").created()).isFalse();
        assertThat(jdbc.queryForObject("SELECT carryover_from_prev FROM hrms.leave_balances WHERE emp_id = 37 "
                + "AND leave_type_id = 1 AND calendar_year = 2026", Double.class)).isEqualTo(5.0);

        // OQ-15: unusable credentials + one-time links for every ACTIVE employee
        int active = jdbc.queryForObject("SELECT count(*) FROM hrms.employees WHERE employment_status = 'ACTIVE' "
                + "AND active_flag", Integer.class);
        assertThat(resets.issueInitialResets("system:cutover").issued()).isEqualTo(active);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.notification_queue WHERE subject = "
                + "'HRMS password setup' AND status = 'PENDING'", Integer.class)).isEqualTo(active);

        // Not repeatable onto a loaded schema
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT legacy_stage.load()", Long.class))
                .hasMessageContaining("not empty");
        assertThat(jdbc.queryForObject("SELECT legacy_stage.oracle_export_script()", String.class))
                .contains("SPOOL employees.csv", "TO_CHAR(HIRE_DATE, 'YYYY-MM-DD') AS HIRE_DATE",
                        "NULL AS TOKEN_HASH", "NULL AS PHOTO_BLOB");
        assertThat(jdbc.queryForObject("SELECT legacy_stage.copy_script('/data/export')", String.class))
                .contains("\\copy legacy_stage.employees FROM '/data/export/employees.csv' WITH (FORMAT csv, HEADER true)");
    }

    /** The seed as the Oracle export: stage rows as text, flags as 'Y'/'N'. Returns the number of employees. */
    private int stageLegacyExport() throws IOException {
        String seed = Files.readString(SEED);
        Matcher m = INSERT.matcher(seed);
        while (m.find()) {
            jdbc.execute("INSERT INTO legacy_stage." + m.group(1));
        }
        Matcher u = UPDATE.matcher(seed);
        while (u.find()) {
            jdbc.update("UPDATE legacy_stage.departments SET manager_emp_id = ? WHERE dept_id = ?", u.group(1),
                    u.group(2));
        }
        for (Map<String, Object> c : jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = 'hrms' AND data_type = 'boolean'
                  AND table_name IN (SELECT table_name FROM legacy_stage.legacy_tables)""")) {
            jdbc.execute(String.format("UPDATE legacy_stage.%s SET %s = CASE %2$s WHEN 'true' THEN 'Y' "
                    + "WHEN 'false' THEN 'N' ELSE %2$s END", c.get("table_name"), c.get("column_name")));
        }
        return jdbc.queryForObject("SELECT count(*) FROM legacy_stage.employees", Integer.class);
    }

    /** Known legacy defects the rehearsal must handle. */
    private void addLegacyDefects() {
        jdbc.update("UPDATE legacy_stage.leave_types SET accrual_frequency = 'DAILY' WHERE leave_type_id = '6'"); // DEF-24
        jdbc.update("UPDATE legacy_stage.employees SET email = 'Alex.Taylor@company.com' WHERE emp_id = '36'"); // OQ-06
        jdbc.update("UPDATE legacy_stage.employees SET email = 'x@legacy.c' WHERE emp_id = '24'"); // DEF-04
        jdbc.update("UPDATE legacy_stage.employees SET ssn_encrypted = 'q83v...legacy-aes' WHERE emp_id = '37'");
        jdbc.update("UPDATE legacy_stage.holidays SET floating_flag = NULL WHERE holiday_id = '1'");
        jdbc.update("""
                INSERT INTO legacy_stage.holidays (holiday_id, holiday_date, holiday_name, active_flag, created_by)
                VALUES ('11', '2026-07-04', 'Independence Day', 'Y', 'HRMS')""");  // a Saturday, no observed row
        jdbc.update("""
                INSERT INTO legacy_stage.review_cycles (cycle_id, cycle_name, cycle_year, start_date, end_date, status,
                    created_by) VALUES ('50', 'FY2025', '2025', '2025-01-01', '2025-12-31', 'CLOSED', 'HRMS')""");
        // generate_reviews_for_cycle re-run: two reviews for emp 37 (the rated one is kept), one for emp 36
        jdbc.update("""
                INSERT INTO legacy_stage.performance_reviews (review_id, cycle_id, emp_id, reviewer_emp_id, review_type,
                    status, overall_rating, created_by)
                VALUES ('500', '50', '37', '31', 'ANNUAL', 'NOT_STARTED', NULL, 'HRMS'),
                       ('501', '50', '37', '31', 'ANNUAL', 'COMPLETED', '4.0', 'HRMS'),
                       ('503', '50', '36', '31', 'ANNUAL', 'NOT_STARTED', NULL, 'HRMS')""");
        jdbc.update("""
                INSERT INTO legacy_stage.performance_goals (goal_id, review_id, emp_id, goal_title, progress_pct,
                    status, created_by)
                VALUES ('900', '500', '37', 'Legacy goal', '120', 'COMPLETED', 'HRMS')""");
        // legacy monthly accrual (Feb 2026), re-run once for emp 37 PTO (the legacy double accrual)
        jdbc.update("""
                INSERT INTO legacy_stage.leave_accrual_log (accrual_id, emp_id, leave_type_id, accrual_date,
                    accrual_amount, balance_after, run_id, created_by)
                VALUES ('1', '37', '1', '2026-02-01', '1.25', '6.25', '77', 'HRMS'),
                       ('2', '37', '1', '2026-02-01', '1.25', '7.50', '78', 'HRMS'),
                       ('3', '36', '1', '2026-02-01', '1.25', '1.25', '77', 'HRMS')""");
        // legacy process_carryover(2025) already ran
        jdbc.update("""
                INSERT INTO legacy_stage.leave_balances (balance_id, emp_id, leave_type_id, calendar_year,
                    opening_balance, accrued, used, adjustment, pending, carryover_from_prev, carryover_expiry_dt,
                    created_by)
                VALUES ('1', '37', '1', '2025', '10', '15', '20', '0', '0', '0', NULL, 'HRMS'),
                       ('2', '37', '1', '2026', '5', '2.5', '0', '0', '0', '5', '2026-04-01', 'HRMS')""");
        jdbc.update("""
                INSERT INTO legacy_stage.user_sessions (session_id, emp_id, username, login_time, session_status,
                    created_date) VALUES ('1', '37', 'ALEX.TAYLOR@COMPANY.COM', '2025-12-01T08:00:00', 'ACTIVE',
                    '2025-12-01T08:00:00')""");
    }

    private List<String> issues(String severity) {
        return jdbc.queryForList("SELECT check_name || ':' || table_name FROM legacy_stage.migration_issues "
                + "WHERE severity = ?", String.class, severity);
    }
}
