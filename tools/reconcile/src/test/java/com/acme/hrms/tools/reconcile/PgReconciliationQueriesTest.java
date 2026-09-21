package com.acme.hrms.tools.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The six PostgreSQL reconciliation queries run on the migrated + seeded P0 baseline. */
class PgReconciliationQueriesTest {

  private static final LocalDate AS_OF = LocalDate.of(2024, 6, 30);
  private static final java.util.Set<String> POPULATED_ON_SEED =
      java.util.Set.of("VW_ACTIVE_EMPLOYEES", "VW_ORG_HIERARCHY", "VW_EMPLOYEE_COMPENSATION");
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void seed() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
  }

  @Test
  void allSixQueriesExecuteAndProduceRows() throws Exception {
    Path root = HrmsPostgres.repoRoot().resolve("tests/reconciliation");
    List<ViewQuery> queries = ViewQuery.all(root);
    assertThat(queries).hasSize(6);
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      List<Cell> cells = ReconcileMain.capture(c, queries, AS_OF, false);
      for (String v : ViewQuery.VIEWS) {
        // the frozen seed only populates employees + salary_records; leave/payroll/approval
        // views are legitimately empty until the module seeds arrive in later phases
        boolean optional = !POPULATED_ON_SEED.contains(v);
        long rows =
            cells.stream().filter(x -> x.view().equals(v)).mapToInt(Cell::rowNo).max().orElse(0);
        if (!optional) {
          assertThat(rows).as(v).isPositive();
        }
      }
      // one row per active employee in the directory
      Integer active =
          jdbc.queryForObject(
              "select count(*) from employees where employment_status='ACTIVE' and active_flag='Y'",
              Integer.class);
      long directoryRows =
          cells.stream()
              .filter(x -> x.view().equals("VW_ACTIVE_EMPLOYEES"))
              .mapToInt(Cell::rowNo)
              .max()
              .orElse(0);
      assertThat(directoryRows).isEqualTo(active.longValue());
    }
  }

  @Test
  void orgHierarchyCoversEveryActiveEmployeeExactlyOnceAndHasNoCycles() throws Exception {
    Path root = HrmsPostgres.repoRoot().resolve("tests/reconciliation");
    ViewQuery hierarchy = ViewQuery.all(root).get(1);
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      List<Cell> cells = ReconcileMain.capture(c, List.of(hierarchy), AS_OF, false);
      List<String> ids =
          cells.stream().filter(x -> x.column().equals("EMP_ID")).map(Cell::value).toList();
      Integer active =
          jdbc.queryForObject(
              "select count(*) from employees where employment_status='ACTIVE'", Integer.class);
      assertThat(ids).doesNotHaveDuplicates().hasSize(active);
      assertThat(
              cells.stream()
                  .filter(x -> x.column().equals("ORG_LEVEL") && x.value().equals("1"))
                  .count())
          .isEqualTo(
              jdbc.queryForObject(
                  "select count(*) from employees where employment_status='ACTIVE' and manager_emp_id is null",
                  Long.class));
    }
  }

  @Test
  void baselineRoundTripsAndDiffIsEmptyAgainstItself() throws Exception {
    Path root = HrmsPostgres.repoRoot().resolve("tests/reconciliation");
    Path tmp = java.nio.file.Files.createTempFile("views-baseline", ".csv");
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      List<Cell> cells = ReconcileMain.capture(c, ViewQuery.all(root), AS_OF, false);
      Baseline.write(tmp, cells);
      List<Cell> back = Baseline.read(tmp);
      assertThat(back).containsExactlyElementsOf(cells);
      assertThat(Diff.of(cells, back).reconciled()).isTrue();
    }
  }

  @Test
  void committedBaselineFileHasTheCanonicalHeader() throws Exception {
    Path csv = HrmsPostgres.repoRoot().resolve("tests/golden/views-baseline.csv");
    assertThat(java.nio.file.Files.readAllLines(csv).get(0)).isEqualTo(Baseline.HEADER);
  }
}
