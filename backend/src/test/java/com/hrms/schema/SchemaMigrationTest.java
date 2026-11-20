package com.hrms.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Boots the Spring context so Flyway applies db/migration + db/seed to PostgreSQL 16. */
@SpringBootTest
class SchemaMigrationTest {

    private static final List<String> TABLES = List.of(
            "departments", "locations", "job_grades", "job_titles", "employees", "employee_history",
            "employee_dependents", "emergency_contacts",
            "salary_records", "pay_elements", "employee_pay_elements", "pay_periods", "payroll_runs",
            "payroll_details", "tax_brackets", "employee_tax_info", "employee_bank_accounts",
            "leave_types", "leave_balances", "leave_requests", "leave_accrual_log", "holidays",
            "review_cycles", "performance_reviews", "performance_goals", "audit_log",
            "system_parameters", "notification_queue", "user_sessions", "lookup_values",
            "user_credentials", "permission_rules", "leave_accrual_runs");

    private static final List<String> VIEWS = List.of(
            "vw_active_employees", "vw_org_hierarchy", "vw_employee_compensation",
            "vw_leave_summary", "vw_payroll_latest", "vw_pending_approvals");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPostgres16.newDatabaseUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Flyway flyway;

    @Test
    void serverIsPostgres16() {
        assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class)).isBetween(160000, 169999);
    }

    @Test
    void allMigrationsAppliedAndValid() {
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
        assertThat(flyway.info().applied()).extracting(m -> m.getScript())
                .contains("R__dev_seed.sql", "V8__app_role_grants.sql");
    }

    @Test
    void everyLegacyTableAndViewExists() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'hrms' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'",
                String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
        List<String> views = jdbc.queryForList(
                "SELECT table_name FROM information_schema.views WHERE table_schema = 'hrms'", String.class);
        assertThat(views).containsExactlyInAnyOrderElementsOf(VIEWS);
    }

    @Test
    void flagColumnsAreNotNullBooleans() {
        List<Map<String, Object>> flags = jdbc.queryForList("""
                SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'hrms' AND table_name NOT LIKE 'vw_%'
                AND (column_name LIKE '%\\_flag' OR column_name IN
                     ('overtime_eligible', 'benefits_enrolled', 'employer_paid', 'prenote_sent',
                      'requires_approval', 'requires_document'))
                """);
        assertThat(flags).hasSize(32);
        assertThat(flags).allSatisfy(c -> {
            assertThat(c.get("data_type")).as(c.toString()).isEqualTo("boolean");
            assertThat(c.get("is_nullable")).as(c.toString()).isEqualTo("NO");
        });
    }

    @Test
    void typeMappingHasNoOracleLeftovers() {
        assertThat(jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'hrms' AND table_name <> 'flyway_schema_history' AND data_type IN
                      ('timestamp without time zone', 'real', 'double precision', 'character')
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'hrms' AND table_name NOT LIKE 'vw_%'
                AND (column_name IN ('created_by', 'modified_by', 'changed_by', 'closed_by',
                                     'submitted_by', 'username')
                     OR (column_name = 'approved_by' AND data_type = 'character varying'))
                AND (data_type <> 'character varying' OR character_maximum_length <> 100)
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'hrms' AND table_name NOT LIKE 'vw_%'
                AND (column_name LIKE '%\\_id' OR column_name = 'approved_by') AND data_type NOT IN ('bigint', 'character varying')
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'hrms' AND table_name = 'audit_log' AND column_name = 'new_values'
                """, String.class)).isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema = 'hrms'
                AND table_name NOT LIKE 'vw_%' AND data_type = 'date'
                AND column_name IN ('created_date', 'modified_date', 'login_time', 'approval_date', 'run_date')
                """, Integer.class)).isZero();
    }

    @Test
    void identitiesStartAtOracleSequenceStart() {
        Map<String, Long> expected = Map.of(
                "departments", 100L, "job_grades", 100L, "job_titles", 100L, "employees", 10000L,
                "salary_records", 1L, "audit_log", 1L);
        expected.forEach((table, start) -> assertThat(jdbc.queryForObject("""
                SELECT s.seqstart FROM pg_sequence s
                WHERE s.seqrelid = pg_get_serial_sequence('hrms.' || ?, (
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'hrms' AND table_name = ? AND is_identity = 'YES'))::regclass
                """, Long.class, table, table)).as(table).isEqualTo(start));
    }

    @Test
    void seedLoadedWithDdlReconciliation() {
        assertThat(count("locations")).isEqualTo(3);
        assertThat(count("job_grades")).isEqualTo(10);
        assertThat(count("departments")).isEqualTo(10);
        assertThat(count("job_titles")).isEqualTo(26);
        assertThat(count("employees")).isEqualTo(24);
        assertThat(count("salary_records")).isEqualTo(23);
        assertThat(count("leave_types")).isEqualTo(6);
        assertThat(count("pay_elements")).isEqualTo(11);
        assertThat(count("holidays")).isEqualTo(10);
        assertThat(count("system_parameters")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT phone_number FROM hrms.locations WHERE location_code = 'HQ'",
                String.class)).isEqualTo("212-555-1000");
        assertThat(jdbc.queryForObject("SELECT grade_code FROM hrms.job_grades WHERE grade_id = 8",
                String.class)).isEqualTo("G08");
        assertThat(jdbc.queryForObject(
                "SELECT param_description FROM hrms.system_parameters WHERE param_code = 'APP_VERSION'",
                String.class)).isEqualTo("Application version");
        assertThat(jdbc.queryForObject(
                "SELECT editable_flag FROM hrms.system_parameters WHERE param_code = 'APP_VERSION'",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT manager_emp_id FROM hrms.departments WHERE dept_id = 30",
                Long.class)).isEqualTo(30L);
        assertThat(jdbc.queryForObject("SELECT accrual_rate FROM hrms.leave_types WHERE leave_type_code = 'SICK'",
                BigDecimal.class)).isEqualByComparingTo("0.83");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM hrms.employees WHERE created_by <> 'system:seed'", Integer.class)).isZero();
    }

    @Test
    void identitiesMovedPastSeededIds() {
        tx.executeWithoutResult(s -> {
            assertThat(nextId("salary_records")).isEqualTo(44L);
            assertThat(nextId("pay_elements")).isEqualTo(206L);
            assertThat(nextId("leave_types")).isEqualTo(7L);
            assertThat(nextId("holidays")).isEqualTo(11L);
            assertThat(nextId("system_parameters")).isEqualTo(11L);
            assertThat(nextId("employees")).isEqualTo(10000L);
            assertThat(nextId("departments")).isEqualTo(100L);
            assertThat(nextId("leave_requests")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT last_value FROM hrms.seq_emp_number", Long.class)).isEqualTo(1000L);
            assertThat(jdbc.queryForObject("SELECT is_called FROM hrms.seq_emp_number", Boolean.class)).isFalse();
            s.setRollbackOnly();
        });
    }

    @Test
    void seedIsIdempotent() throws Exception {
        String seed = new ClassPathResource("db/seed/R__dev_seed.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(seed);
        assertThat(count("employees")).isEqualTo(24);
        assertThat(count("salary_records")).isEqualTo(23);
    }

    @Test
    void leaveBalanceAvailableIsStoredGeneratedColumn() {
        assertThat(jdbc.queryForObject("""
                SELECT is_generated || ':' || generation_expression FROM information_schema.columns
                WHERE table_schema = 'hrms' AND table_name = 'leave_balances' AND column_name = 'available'
                """, String.class))
                .isEqualTo("ALWAYS:((((opening_balance + accrued) - used) + adjustment) - pending)");
        tx.executeWithoutResult(s -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, opening_balance,
                        accrued, used, adjustment, pending, created_by)
                    VALUES (11, 1, 2026, 5, 10, 3.5, -1, 2, 'test') RETURNING balance_id
                    """, Long.class);
            assertThat(jdbc.queryForObject("SELECT available FROM hrms.leave_balances WHERE balance_id = ?",
                    BigDecimal.class, id)).isEqualByComparingTo("8.50");
            jdbc.update("UPDATE hrms.leave_balances SET pending = 0 WHERE balance_id = ?", id);
            assertThat(jdbc.queryForObject("SELECT available FROM hrms.leave_balances WHERE balance_id = ?",
                    BigDecimal.class, id)).isEqualByComparingTo("10.50");
            s.setRollbackOnly();
        });
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, available, created_by)
                VALUES (11, 1, 2027, 1, 'test')
                """)).rootCause().hasMessageContaining("cannot insert a non-DEFAULT value into column \"available\"");
    }

    @Test
    void activeEmailIsUniqueCaseInsensitively() {
        assertThatThrownBy(() -> insertEmployee("EMP-900001", "James.Richardson@Company.com", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_employees_email_active");
        tx.executeWithoutResult(s -> {
            // An inactive row may reuse the address (legacy TRG-004 checked ACTIVE_FLAG = 'Y' rows only).
            insertEmployee("EMP-900002", "james.richardson@company.com", false);
            s.setRollbackOnly();
        });
    }

    @Test
    void checkConstraintsMakeNullSemanticsExplicit() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.leave_types (leave_type_code, leave_type_name, accrual_frequency, created_by)
                VALUES ('X1', 'x', 'WEEKLY', 'test')
                """)).hasMessageContaining("chk_accrual_freq");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.leave_requests (emp_id, leave_type_id, start_date, end_date, total_days,
                    half_day_period, created_by)
                VALUES (11, 1, DATE '2026-12-01', DATE '2026-12-01', 0.5, 'NOON', 'test')
                """)).hasMessageContaining("chk_half_day");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.leave_requests (emp_id, leave_type_id, start_date, end_date, total_days, created_by)
                VALUES (11, 1, DATE '2026-12-02', DATE '2026-12-01', 1, 'test')
                """)).hasMessageContaining("chk_lr_dates");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.performance_reviews (cycle_id, emp_id, reviewer_emp_id, overall_rating, created_by)
                VALUES (1, 11, 10, 5.5, 'test')
                """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE hrms.employees SET gender = 'X' WHERE emp_id = 11"))
                .hasMessageContaining("chk_emp_gender");
    }

    @Test
    void auditLogAcceptsStatusChangeWithJsonObjects() {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO hrms.audit_log (table_name, record_id, action_type, old_values, new_values, changed_by)
                    VALUES ('LEAVE_REQUESTS', 1, 'STATUS_CHANGE', '{"status":"PENDING"}', '{"status":"APPROVED"}',
                            'patricia.williams@company.com')
                    """);
            assertThat(jdbc.queryForObject(
                    "SELECT new_values ->> 'status' FROM hrms.audit_log WHERE action_type = 'STATUS_CHANGE'",
                    String.class)).isEqualTo("APPROVED");
            s.setRollbackOnly();
        });
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.audit_log (table_name, record_id, action_type, new_values, changed_by)
                VALUES ('X', 1, 'STATUS_CHANGE', '[1]', 'test')
                """)).hasMessageContaining("chk_audit_new_object");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hrms.audit_log (table_name, record_id, action_type, changed_by)
                VALUES ('X', 1, 'PURGE', 'test')
                """)).hasMessageContaining("chk_audit_action");
    }

    @Test
    void appRoleCannotDeleteAnything() {
        assertThat(jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'hrms' AND c.relkind = 'r'
                AND (has_table_privilege('hrms_app', c.oid, 'DELETE')
                     OR has_table_privilege('hrms_app', c.oid, 'TRUNCATE')
                     OR NOT has_table_privilege('hrms_app', c.oid, 'SELECT, INSERT, UPDATE'))
                """, String.class)).isEmpty();
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL ROLE hrms_app");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.vw_active_employees", Integer.class))
                    .isEqualTo(23);
            assertThat(jdbc.queryForObject("SELECT nextval('hrms.seq_emp_number')", Long.class)).isEqualTo(1000L);
            insertEmployee("EMP-900003", "new.hire@company.com", true);
            s.setRollbackOnly();
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL ROLE hrms_app");
            jdbc.update("DELETE FROM hrms.employees WHERE emp_id = 99");
        })).rootCause().hasMessageContaining("permission denied for table employees");
    }

    @Test
    void orgHierarchyKeepsConnectBySemantics() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM hrms.vw_org_hierarchy");
        assertThat(rows).hasSize(23);
        assertThat(rows.get(0)).containsEntry("emp_id", 1L).containsEntry("org_level", 1)
                .containsEntry("org_path", " > JAMES RICHARDSON").containsEntry("is_leaf", false);
        assertThat(rows.get(1).get("emp_name")).isEqualTo("SARAH CHEN");
        assertThat(rows).extracting(r -> r.get("emp_id")).doesNotContain(99L);
        Map<String, Object> maria = rows.stream().filter(r -> r.get("emp_id").equals(32L)).findFirst().orElseThrow();
        assertThat(maria).containsEntry("org_level", 5)
                .containsEntry("org_path", " > JAMES RICHARDSON > MICHAEL OCONNOR > RACHEL THOMPSON > KEVIN GARCIA > MARIA RODRIGUEZ")
                .containsEntry("is_leaf", true);
        Map<String, Object> kevin = rows.stream().filter(r -> r.get("emp_id").equals(31L)).findFirst().orElseThrow();
        assertThat(kevin).containsEntry("is_leaf", false);
    }

    @Test
    void reportingViewsAreQueryable() {
        Map<String, Object> james = jdbc.queryForMap("SELECT * FROM hrms.vw_active_employees WHERE emp_id = 1");
        assertThat(james).containsEntry("full_name", "JAMES RICHARDSON").containsEntry("grade_name", "C-Suite");
        assertThat((BigDecimal) james.get("current_salary")).isEqualByComparingTo("450000");
        assertThat(jdbc.queryForObject("SELECT compa_ratio FROM hrms.vw_employee_compensation WHERE emp_id = 1",
                BigDecimal.class)).isEqualByComparingTo("100.0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.vw_payroll_latest", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.vw_leave_summary", Integer.class)).isZero();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO hrms.leave_requests (emp_id, leave_type_id, start_date, end_date, total_days,
                        approver_emp_id, created_by)
                    VALUES (11, 1, DATE '2026-12-07', DATE '2026-12-09', 3.0, 10, 'david.martinez@company.com')
                    """);
            assertThat(jdbc.queryForMap("SELECT * FROM hrms.vw_pending_approvals"))
                    .containsEntry("approval_type", "LEAVE").containsEntry("approver_id", 10L)
                    .containsEntry("requestor_name", "DAVID MARTINEZ")
                    .containsEntry("item_description", "Paid Time Off")
                    .containsEntry("details", "3 day(s) 12/07-12/09");
            s.setRollbackOnly();
        });
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM hrms." + table, Integer.class);
    }

    private Long nextId(String table) {
        return jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('hrms." + table + "', ("
                + "SELECT column_name FROM information_schema.columns WHERE table_schema = 'hrms' "
                + "AND table_name = '" + table + "' AND is_identity = 'YES')))", Long.class);
    }

    private void insertEmployee(String empNumber, String email, boolean active) {
        jdbc.update("""
                INSERT INTO hrms.employees (emp_number, first_name, last_name, email, hire_date, dept_id, job_id,
                    employment_status, active_flag, created_by)
                VALUES (?, 'TEST', 'USER', ?, DATE '2026-11-19', 10, 53, ?, ?, 'test')
                """, empNumber, email, active ? "ACTIVE" : "TERMINATED", active);
    }
}
