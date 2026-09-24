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
   * Oracle applies the view's {@code WHERE EMPLOYMENT_STATUS = 'ACTIVE'} after {@code CONNECT BY}:
   * the active reports of a terminated manager stay in VW_ORG_HIERARCHY (LEVEL counts the
   * terminated node, SYS_CONNECT_BY_PATH names it) and CONNECT_BY_ISLEAF counts terminated
   * children. tests/golden/scenarios/terminated-mid-manager.sql terminates emp 21, the only report
   * of emp 20 and the manager of 22/23/24; the resulting capture must reconcile with the committed
   * tests/golden/views-terminated-mid-manager.csv.
   */
  @Test
  void orgHierarchyKeepsActiveReportsOfTerminatedManagerLikeOracle() throws Exception {
    Path root = HrmsPostgres.repoRoot().resolve("tests/reconciliation");
    Path scenario = HrmsPostgres.repoRoot().resolve("tests/golden/scenarios");
    Path golden = HrmsPostgres.repoRoot().resolve("tests/golden/views-terminated-mid-manager.csv");
    try {
      for (String stmt :
          HrmsPostgres.splitStatements(
              java.nio.file.Files.readString(scenario.resolve("terminated-mid-manager.sql")))) {
        jdbc.execute(stmt);
      }
      Map<String, Map<String, String>> rows = new HashMap<>();
      try (Connection c = HrmsPostgres.dataSource().getConnection()) {
        List<Cell> cells =
            ReconcileMain.capture(c, List.of(ViewQuery.all(root).get(1)), AS_OF, false);
        Map<Integer, Map<String, String>> byRow = new HashMap<>();
        for (Cell x : cells) {
          byRow.computeIfAbsent(x.rowNo(), k -> new HashMap<>()).put(x.column(), x.value());
        }
        byRow.values().forEach(r -> rows.put(r.get("EMP_ID"), r));
      }
      assertThat(rows).doesNotContainKey("21");
      assertThat(rows.get("20")).containsEntry("IS_LEAF", "0").containsEntry("ORG_LEVEL", "3");
      for (String report : List.of("22", "23", "24")) {
        Map<String, String> r = rows.get(report);
        assertThat(r).as("emp " + report + " must stay in the view").isNotNull();
        assertThat(r).containsEntry("ORG_LEVEL", "5").containsEntry("MANAGER_EMP_ID", "21");
        assertThat(r.get("ORG_PATH"))
            .startsWith(" > JAMES RICHARDSON > SARAH CHEN > ROBERT KUMAR > JENNIFER PARK > ");
      }
      Integer active =
          jdbc.queryForObject(
              "select count(*) from employees where employment_status='ACTIVE'", Integer.class);
      assertThat(rows).hasSize(active);

      List<Cell> expected = Baseline.read(golden);
      for (String v : ViewQuery.VIEWS) {
        assertThat(expected.stream().anyMatch(x -> x.view().equals(v))).as(v).isTrue();
      }
      try (Connection c = HrmsPostgres.dataSource().getConnection()) {
        List<Cell> actual = ReconcileMain.capture(c, ViewQuery.all(root), AS_OF, false);
        Diff diff = Diff.of(expected, actual);
        assertThat(diff.reconciled()).as(diff.toMarkdown()).isTrue();
      }
    } finally {
      HrmsPostgres.resetSchema();
      HrmsPostgres.loadFixtures(jdbc);
    }
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

  /**
   * CUTOVER_PLAN.md §9.2 / P5 contract VAL-05: the post-change re-baseline of VW_LEAVE_SUMMARY
   * (tests/reconciliation/pg-p5) subtracts PENDING from AVAILABLE. Every other cell must be
   * identical to the P0 baseline, and AVAILABLE must differ from it by exactly PENDING – so the
   * committed P5 golden is provably derived from the P0 one (tests/golden/README.md). Regenerate
   * with {@code -Dhrms.golden.update=true}.
   */
  @Test
  void p5LeaveSummaryRebaselineSubtractsPendingFromAvailable() throws Exception {
    Path repo = HrmsPostgres.repoRoot();
    Path golden = repo.resolve("tests/golden/views-baseline-p5-leave-summary.csv");
    ViewQuery p5 =
        new ViewQuery(
            "VW_LEAVE_SUMMARY",
            repo.resolve("tests/reconciliation/oracle/vw_leave_summary.sql"),
            repo.resolve("tests/reconciliation/pg-p5/vw_leave_summary.sql"));
    List<Cell> actual;
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      actual = ReconcileMain.capture(c, List.of(p5), AS_OF, false);
    }
    if (Boolean.getBoolean("hrms.golden.update")) {
      Baseline.write(golden, actual);
    }
    List<Cell> expected = Baseline.read(golden);
    Diff diff = Diff.of(expected, actual);
    assertThat(diff.reconciled()).as(diff.toMarkdown()).isTrue();

    Map<String, String> p0 = new HashMap<>();
    for (Cell x : Baseline.read(repo.resolve("tests/golden/views-baseline.csv"))) {
      if (x.view().equals("VW_LEAVE_SUMMARY")) {
        p0.put(x.rowNo() + "/" + x.column(), x.value());
      }
    }
    Map<String, String> p5Cells = new HashMap<>();
    expected.forEach(x -> p5Cells.put(x.rowNo() + "/" + x.column(), x.value()));
    assertThat(p5Cells.keySet()).isEqualTo(p0.keySet());
    int shifted = 0;
    for (Map.Entry<String, String> e : p5Cells.entrySet()) {
      String key = e.getKey();
      if (key.endsWith("/AVAILABLE")) {
        String row = key.substring(0, key.indexOf('/'));
        BigDecimal pending = new BigDecimal(p0.get(row + "/PENDING"));
        BigDecimal legacy = new BigDecimal(p0.get(key));
        assertThat(new BigDecimal(e.getValue()))
            .as("row " + row)
            .isEqualByComparingTo(legacy.subtract(pending));
        if (pending.signum() != 0) {
          shifted++;
        }
      } else {
        assertThat(e.getValue()).as(key).isEqualTo(p0.get(key));
      }
    }
    assertThat(shifted).as("seed rows with PENDING > 0").isEqualTo(3);
  }
}
