package com.acme.hrms.tools.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CUTOVER_PLAN.md §7.3 – PostgreSQL leg of the employee-group data migration: EMPLOYEES →
 * EMPLOYEE_HISTORY → EMPLOYEE_DEPENDENTS → EMERGENCY_CONTACTS → SALARY_RECORDS in FK order, keyed
 * upsert, PK sequences and {@code SEQ_EMP_NUMBER} restarted above the loaded data, reverse-extract
 * MERGE that never sends the PostgreSQL-only {@code employees.version} / {@code
 * salary_records.out_of_grade_band} to Oracle. The Oracle legs (AS OF SCN extract, MERGE apply) are
 * untested-live: no Oracle here.
 */
class EmployeeCutoverTest {

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void seed() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
  }

  @Test
  void groupLoadsEmployeesBeforeTheirChildrenAndRestartsEverySequence() throws Exception {
    List<String> tables = TableGroup.byFlag("employee").tables();
    assertThat(tables)
        .startsWith("employees")
        .contains(
            "employee_history", "employee_dependents", "emergency_contacts", "salary_records");
    assertThat(tables.indexOf("employees"))
        .isLessThan(tables.indexOf("employee_history"))
        .isLessThan(tables.indexOf("employee_dependents"))
        .isLessThan(tables.indexOf("emergency_contacts"))
        .isLessThan(tables.indexOf("salary_records"));
    // departments / job_titles / locations referenced by employees come from the REFERENCE group
    assertThat(TableGroup.REFERENCE.tables()).contains("departments", "job_titles", "locations");

    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      for (String t :
          List.of(
              "employees",
              "employee_history",
              "employee_dependents",
              "emergency_contacts",
              "salary_records")) {
        String pk = TableGroup.PRIMARY_KEYS.get(t);
        assertThat(BulkLoader.restartSequence(c, t))
            .as(t)
            .isEqualTo(
                jdbc.queryForObject(
                    "select coalesce(max(" + pk + "), 0) + 1 from " + t, Long.class));
      }
    }
  }

  @Test
  void empNumberSequenceIsRestartedAboveTheHighestLoadedNumber() throws Exception {
    jdbc.update(
        "insert into employees (emp_id, emp_number, first_name, last_name, hire_date, dept_id,"
            + " job_id, employment_status, active_flag, created_by)"
            + " values (777000, 'EMP-004242', 'CUT', 'OVER', date '2020-01-01', 1, 1, 'ACTIVE',"
            + " 'Y', 'CDC')");
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      assertThat(BulkLoader.restartBusinessKeySequence(c, "employees")).isEqualTo(4243L);
      assertThat(BulkLoader.restartBusinessKeySequence(c, "employee_history")).isEqualTo(-1L);
    }
    assertThat(jdbc.queryForObject("select nextval('seq_emp_number')", Long.class))
        .isEqualTo(4243L);
    jdbc.update("delete from employees where emp_id = 777000");
    // seed numbers are below 1000: the sequence never restarts below its declared start
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      assertThat(BulkLoader.restartBusinessKeySequence(c, "employees")).isEqualTo(1000L);
    }
  }

  @Test
  void bulkUpsertWritesEveryEmployeeColumnAndIsIdempotent() throws Exception {
    List<String> cols =
        List.of(
            "emp_id",
            "emp_number",
            "first_name",
            "last_name",
            "email",
            "hire_date",
            "dept_id",
            "job_id",
            "employment_status",
            "active_flag",
            "created_by");
    String sql = BulkLoader.upsertSql("employees", cols);
    assertThat(sql).contains("on conflict (emp_id)");
    Object[] row = {
      777001L,
      "EMP-777001",
      "IDEM",
      "POTENT",
      "idem.potent@company.com",
      java.sql.Date.valueOf("2021-02-03"),
      1,
      1,
      "ACTIVE",
      "Y",
      "CDC"
    };
    jdbc.update(sql, row);
    jdbc.update(sql, row);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from employees where emp_number = 'EMP-777001'", Integer.class))
        .isEqualTo(1);
    // version is PostgreSQL-only: defaults to 0 for loaded rows, never part of the load
    assertThat(
            jdbc.queryForObject(
                "select version from employees where emp_id = 777001", Integer.class))
        .isZero();
    jdbc.update("delete from employees where emp_id = 777001");
  }

  @Test
  void reverseExtractNeverSendsPostgresOnlyColumnsToOracle() throws Exception {
    try (Connection c = HrmsPostgres.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement("select * from employees where 1 = 0");
        ResultSet rs = ps.executeQuery()) {
      List<String> cols = new ArrayList<>();
      for (int i : ReverseExtract.oracleWritableColumns("employees", rs.getMetaData())) {
        cols.add(rs.getMetaData().getColumnLabel(i));
      }
      assertThat(cols)
          .doesNotContain("version")
          .contains("emp_id", "emp_number", "ssn_encrypted", "employment_status", "active_flag");
      assertThat(BulkLoader.writableColumns("employees", rs.getMetaData()))
          .hasSize(rs.getMetaData().getColumnCount());
    }
    try (Connection c = HrmsPostgres.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement("select * from salary_records where 1 = 0");
        ResultSet rs = ps.executeQuery()) {
      List<String> cols = new ArrayList<>();
      for (int i : ReverseExtract.oracleWritableColumns("salary_records", rs.getMetaData())) {
        cols.add(rs.getMetaData().getColumnLabel(i));
      }
      assertThat(cols).doesNotContain("out_of_grade_band").contains("salary_id", "base_salary");
    }
    assertThat(
            ReverseExtract.mergeSql(
                "employees", List.of("EMP_ID", "EMPLOYMENT_STATUS", "ACTIVE_FLAG")))
        .isEqualTo(
            "merge into EMPLOYEES t using (select ? as EMP_ID, ? as EMPLOYMENT_STATUS,"
                + " ? as ACTIVE_FLAG from dual) s on (t.EMP_ID = s.EMP_ID)"
                + " when matched then update set t.EMPLOYMENT_STATUS = s.EMPLOYMENT_STATUS,"
                + " t.ACTIVE_FLAG = s.ACTIVE_FLAG"
                + " when not matched then insert (EMP_ID, EMPLOYMENT_STATUS, ACTIVE_FLAG)"
                + " values (s.EMP_ID, s.EMPLOYMENT_STATUS, s.ACTIVE_FLAG)");
    assertThat(ReverseExtract.mergeSql("employee_history", List.of("HIST_ID", "COMMENTS")))
        .contains("on (t.HIST_ID = s.HIST_ID)");
    assertThat(ReverseExtract.mergeSql("employee_dependents", List.of("DEPENDENT_ID")))
        .contains("on (t.DEPENDENT_ID = s.DEPENDENT_ID)");
    assertThat(ReverseExtract.mergeSql("emergency_contacts", List.of("CONTACT_ID")))
        .contains("on (t.CONTACT_ID = s.CONTACT_ID)");
  }

  @Test
  void historyCommentsColumnMatchesTheOracleDictionaryAfterV6() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name ="
                    + " 'employee_history' and column_name = 'comments'",
                Integer.class))
        .isEqualTo(1);
    // dependents / contacts keep their soft-delete flag: no physical deletes to replicate
    for (String t : List.of("employee_dependents", "emergency_contacts")) {
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from information_schema.columns where table_name = ? and"
                      + " column_name = 'active_flag'",
                  Integer.class,
                  t))
          .as(t)
          .isEqualTo(1);
    }
  }

  @Test
  void checksumGateCoversEveryEmployeeTable() throws Exception {
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      for (String t : TableGroup.EMPLOYEE.tables()) {
        Checksum.TableChecksum a = Checksum.compute(c, t, false);
        assertThat(a.rows())
            .as(t)
            .isEqualTo(jdbc.queryForObject("select count(*) from " + t, Long.class));
      }
    }
  }
}
