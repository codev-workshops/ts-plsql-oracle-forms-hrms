package com.acme.hrms.common.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 1: the Flyway baseline applies from scratch and the seed loads with constraints intact. */
class FlywayBaselineTest {

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateAndSeed() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
  }

  @Test
  void allMigrationsAppliedInOrder() {
    List<String> versions =
        jdbc.queryForList(
            "select version from flyway_schema_history where success order by installed_rank",
            String.class);
    assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");
  }

  @Test
  void everyOracleTableIsTranslatedLowerCase() {
    List<String> tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public' and"
                + " table_type = 'BASE TABLE' order by 1",
            String.class);
    assertThat(tables)
        .contains(
            "departments",
            "locations",
            "job_grades",
            "job_titles",
            "employees",
            "employee_history",
            "employee_dependents",
            "emergency_contacts",
            "salary_records",
            "pay_elements",
            "employee_pay_elements",
            "pay_periods",
            "payroll_runs",
            "payroll_details",
            "tax_brackets",
            "employee_tax_info",
            "employee_bank_accounts",
            "leave_types",
            "leave_balances",
            "leave_requests",
            "leave_accrual_log",
            "holidays",
            "review_cycles",
            "performance_reviews",
            "performance_goals",
            "audit_log",
            "system_parameters",
            "notification_queue",
            "user_sessions",
            "lookup_values",
            "user_accounts",
            "roles",
            "role_permissions",
            "user_roles",
            "refresh_tokens",
            "error_log");
    assertThat(tables).allMatch(t -> t.equals(t.toLowerCase()));
  }

  @Test
  void noTriggersNoPlpgsqlNoViews() {
    Integer triggers =
        jdbc.queryForObject(
            "select count(*) from information_schema.triggers where trigger_schema = 'public'",
            Integer.class);
    Integer routines =
        jdbc.queryForObject(
            "select count(*) from information_schema.routines where routine_schema = 'public'",
            Integer.class);
    Integer views =
        jdbc.queryForObject(
            "select count(*) from information_schema.views where table_schema = 'public'",
            Integer.class);
    assertThat(triggers).isZero();
    assertThat(routines).isZero();
    assertThat(views).isZero();
  }

  @Test
  void sequencesUseCacheOne() {
    List<Map<String, Object>> seqs =
        jdbc.queryForList(
            "select c.relname, s.seqcache from pg_sequence s join pg_class c on c.oid ="
                + " s.seqrelid");
    assertThat(seqs).isNotEmpty();
    assertThat(seqs).allMatch(row -> ((Number) row.get("seqcache")).longValue() == 1L);
    assertThat(seqs).extracting(r -> (String) r.get("relname")).contains("seq_employee");
    Long start =
        jdbc.queryForObject(
            "select seqstart from pg_sequence where seqrelid = 'seq_employee'::regclass",
            Long.class);
    assertThat(start).isEqualTo(10000L);
  }

  @Test
  void leaveBalanceAvailableIsGeneratedStored() {
    String generation =
        jdbc.queryForObject(
            "select is_generated from information_schema.columns where table_name ="
                + " 'leave_balances' and column_name = 'available'",
            String.class);
    assertThat(generation).isEqualTo("ALWAYS");
    jdbc.update(
        "insert into leave_balances (balance_id, emp_id, leave_type_id, calendar_year,"
            + " opening_balance, accrued, used, adjustment, pending, created_by) values"
            + " (nextval('seq_leave_balance'), 1, 1, 2099, 10, 5, 3, 1, 2, 'TEST')");
    BigDecimal available =
        jdbc.queryForObject(
            "select available from leave_balances where calendar_year = 2099", BigDecimal.class);
    assertThat(available).isEqualByComparingTo("11.00");
    jdbc.update("delete from leave_balances where calendar_year = 2099");
  }

  @Test
  void seedLoaded() {
    assertThat(jdbc.queryForObject("select count(*) from employees", Integer.class)).isEqualTo(24);
    assertThat(jdbc.queryForObject("select count(*) from salary_records", Integer.class))
        .isEqualTo(23);
    assertThat(
            jdbc.queryForObject(
                "select param_value from system_parameters where param_group = 'SECURITY' and"
                    + " param_code = 'PASSWORD_MIN_LENGTH'",
                String.class))
        .isEqualTo("8");
    assertThat(
            jdbc.queryForObject(
                "select param_value from system_parameters where param_group = 'SECURITY' and"
                    + " param_code = 'SESSION_TIMEOUT_MIN'",
                String.class))
        .isEqualTo("30");
    assertThat(jdbc.queryForObject("select count(*) from role_permissions", Integer.class))
        .isEqualTo(20 + 6 + 3 + 5 + 1 + 2);
  }

  @Test
  void transactionalSeedIsNotEmpty() {
    Map<String, Integer> expected =
        Map.of(
            "leave_balances", 13,
            "leave_requests", 5,
            "pay_periods", 2,
            "payroll_runs", 2,
            "payroll_details", 30,
            "review_cycles", 1,
            "performance_reviews", 4,
            "user_accounts", 5,
            "user_roles", 5);
    expected.forEach(
        (table, rows) ->
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
                .as(table)
                .isEqualTo(rows));
    assertThat(
            jdbc.queryForList(
                "select distinct r.role_code from user_roles ur join roles r on r.role_id ="
                    + " ur.role_id order by 1",
                String.class))
        .containsExactly("EXECUTIVE", "MANAGER", "STAFF");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payroll_runs where status = 'APPROVED'", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from leave_requests where status = 'PENDING'", Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from performance_reviews where status = 'MANAGER_REVIEW'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void seedAdvancesSequencesPastExplicitPrimaryKeys() {
    Map<String, String> sequences =
        Map.ofEntries(
            Map.entry("seq_department", "select max(dept_id) from departments"),
            Map.entry("seq_job_grade", "select max(grade_id) from job_grades"),
            Map.entry("seq_job_title", "select max(job_id) from job_titles"),
            Map.entry("seq_employee", "select max(emp_id) from employees"),
            Map.entry("seq_salary", "select max(salary_id) from salary_records"),
            Map.entry("seq_pay_element", "select max(element_id) from pay_elements"),
            Map.entry("seq_pay_period", "select max(period_id) from pay_periods"),
            Map.entry("seq_payroll_run", "select max(run_id) from payroll_runs"),
            Map.entry("seq_payroll_detail", "select max(detail_id) from payroll_details"),
            Map.entry("seq_leave_type", "select max(leave_type_id) from leave_types"),
            Map.entry("seq_leave_balance", "select max(balance_id) from leave_balances"),
            Map.entry("seq_leave_request", "select max(request_id) from leave_requests"),
            Map.entry("seq_holiday", "select max(holiday_id) from holidays"),
            Map.entry("seq_review_cycle", "select max(cycle_id) from review_cycles"),
            Map.entry("seq_perf_review", "select max(review_id) from performance_reviews"),
            Map.entry("seq_system_param", "select max(param_id) from system_parameters"));
    sequences.forEach(
        (seq, maxSql) -> {
          Long max = jdbc.queryForObject(maxSql, Long.class);
          Long next = jdbc.queryForObject("select nextval('" + seq + "')", Long.class);
          assertThat(next).as(seq).isGreaterThan(max);
        });
    // seq_employee starts at 10000 (above the 43 seeded ids) and must not be pulled back
    assertThat(jdbc.queryForObject("select last_value from seq_employee", Long.class))
        .isGreaterThanOrEqualTo(10000L);
  }

  @Test
  void constraintsHold() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into employees (emp_id, emp_number, first_name, last_name, email,"
                        + " hire_date, dept_id, job_id, created_by) values (9999, 'EMP-9999', 'A',"
                        + " 'B', 'JAMES.RICHARDSON@company.com', current_date, 1, 1, 'T')"))
        .as("unique index on lower(email)")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update("update employees set employment_status = 'RETIRED' where emp_id = 1"))
        .as("chk_emp_status")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update employees set dept_id = 424242 where emp_id = 1"))
        .as("fk_emp_dept")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into role_permissions (role_id, authority) values (1, 'payroll:view')"))
        .as("authority pattern")
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
