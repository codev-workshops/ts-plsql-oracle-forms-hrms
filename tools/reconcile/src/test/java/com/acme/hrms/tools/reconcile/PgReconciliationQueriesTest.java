package com.acme.hrms.tools.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The six PostgreSQL reconciliation queries run on the migrated + seeded P0 baseline. */
class PgReconciliationQueriesTest {

  private static final LocalDate AS_OF = LocalDate.of(2024, 6, 30);
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
        long rows =
            cells.stream().filter(x -> x.view().equals(v)).mapToInt(Cell::rowNo).max().orElse(0);
        assertThat(rows).as(v + " must reconcile on real rows, not vacuously").isPositive();
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

  /**
   * tenure_years must follow Oracle {@code TRUNC(MONTHS_BETWEEN(as_of, hire_date) / 12, 1)}: the
   * day-of-month difference contributes {@code (d1 - d2) / 31}, which {@code age()} drops (emp 20,
   * hired 2014-05-12, as of 2024-06-30 is 10.1 on Oracle, not 10).
   */
  @Test
  void tenureYearsKeepsOracleMonthsBetweenDayFraction() throws Exception {
    Path root = HrmsPostgres.repoRoot().resolve("tests/reconciliation");
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      List<Cell> cells =
          ReconcileMain.capture(c, List.of(ViewQuery.all(root).get(0)), AS_OF, false);
      Map<Integer, String> empIdByRow = new HashMap<>();
      Map<Integer, String> hireByRow = new HashMap<>();
      Map<Integer, String> tenureByRow = new HashMap<>();
      for (Cell x : cells) {
        switch (x.column()) {
          case "EMP_ID" -> empIdByRow.put(x.rowNo(), x.value());
          case "HIRE_DATE" -> hireByRow.put(x.rowNo(), x.value());
          case "TENURE_YEARS" -> tenureByRow.put(x.rowNo(), x.value());
          default -> {}
        }
      }
      assertThat(tenureByRow).isNotEmpty();
      boolean sawEmp20 = false;
      for (Integer row : tenureByRow.keySet()) {
        LocalDate hired = LocalDate.parse(hireByRow.get(row));
        String expected = Canonical.number(oracleTenureYears(AS_OF, hired));
        assertThat(tenureByRow.get(row)).as("emp " + empIdByRow.get(row)).isEqualTo(expected);
        if (empIdByRow.get(row).equals("20")) {
          sawEmp20 = true;
          assertThat(hired).isEqualTo(LocalDate.of(2014, 5, 12));
          assertThat(tenureByRow.get(row)).isEqualTo("10.1");
        }
      }
      assertThat(sawEmp20).isTrue();
    }
  }

  /** Reference implementation of Oracle MONTHS_BETWEEN(d1, d2) / 12 truncated to one decimal. */
  static BigDecimal oracleTenureYears(LocalDate d1, LocalDate d2) {
    int wholeMonths =
        (d1.getYear() - d2.getYear()) * 12 + (d1.getMonthValue() - d2.getMonthValue());
    boolean sameDay = d1.getDayOfMonth() == d2.getDayOfMonth();
    boolean bothMonthEnd =
        d1.getDayOfMonth() == d1.lengthOfMonth() && d2.getDayOfMonth() == d2.lengthOfMonth();
    BigDecimal months = BigDecimal.valueOf(wholeMonths);
    if (!sameDay && !bothMonthEnd) {
      months =
          months.add(
              BigDecimal.valueOf(d1.getDayOfMonth() - d2.getDayOfMonth())
                  .divide(BigDecimal.valueOf(31), 20, RoundingMode.HALF_UP));
    }
    return months
        .divide(BigDecimal.valueOf(12), 20, RoundingMode.HALF_UP)
        .setScale(1, RoundingMode.DOWN);
  }

  @Test
  void oracleTenureReferenceMatchesKnownMonthsBetweenValues() {
    assertThat(oracleTenureYears(LocalDate.of(2024, 6, 30), LocalDate.of(2014, 5, 12)))
        .isEqualByComparingTo("10.1");
    // same day-of-month: whole months only
    assertThat(oracleTenureYears(LocalDate.of(2024, 6, 30), LocalDate.of(2014, 6, 30)))
        .isEqualByComparingTo("10.0");
    // both month ends: whole months only (28 Feb vs 30 Jun)
    assertThat(oracleTenureYears(LocalDate.of(2024, 6, 30), LocalDate.of(2023, 2, 28)))
        .isEqualByComparingTo("1.3");
    // negative day fraction: 2024-06-15 vs 2019-06-30 = 60 - 15/31 months
    assertThat(oracleTenureYears(LocalDate.of(2024, 6, 15), LocalDate.of(2019, 6, 30)))
        .isEqualByComparingTo("4.9");
  }

  /**
   * TEST_STRATEGY §5 row 0: the committed golden baseline carries rows for every view and the
   * PostgreSQL queries reconcile against it byte-for-byte (tests/golden/README.md documents how the
   * file was produced).
   */
  @Test
  void committedBaselineIsPopulatedAndReconcilesWithPostgres() throws Exception {
    Path csv = HrmsPostgres.repoRoot().resolve("tests/golden/views-baseline.csv");
    List<String> lines = java.nio.file.Files.readAllLines(csv);
    assertThat(lines.get(0)).isEqualTo(Baseline.HEADER);
    List<Cell> expected = Baseline.read(csv);
    for (String v : ViewQuery.VIEWS) {
      assertThat(expected.stream().anyMatch(x -> x.view().equals(v))).as(v).isTrue();
    }
    // the baseline's TENURE_YEARS must agree with the Oracle MONTHS_BETWEEN reference, so the
    // committed file cannot silently inherit an age()-style PostgreSQL defect
    Map<Integer, LocalDate> hired = new HashMap<>();
    for (Cell x : expected) {
      if (x.view().equals("VW_ACTIVE_EMPLOYEES") && x.column().equals("HIRE_DATE")) {
        hired.put(x.rowNo(), LocalDate.parse(x.value()));
      }
    }
    for (Cell x : expected) {
      if (x.view().equals("VW_ACTIVE_EMPLOYEES") && x.column().equals("TENURE_YEARS")) {
        assertThat(x.value())
            .as("baseline row " + x.rowNo())
            .isEqualTo(Canonical.number(oracleTenureYears(AS_OF, hired.get(x.rowNo()))));
      }
    }
    Path root = HrmsPostgres.repoRoot().resolve("tests/reconciliation");
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      List<Cell> actual = ReconcileMain.capture(c, ViewQuery.all(root), AS_OF, false);
      Diff diff = Diff.of(expected, actual);
      assertThat(diff.reconciled()).as(diff.toMarkdown()).isTrue();
    }
  }
}
