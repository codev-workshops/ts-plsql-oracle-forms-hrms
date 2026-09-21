package com.acme.hrms.tools.reconcile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * tools/reconcile runner (CUTOVER_PLAN.md §4.2 item 0.5b; TEST_STRATEGY.md §2.3).
 *
 * <pre>
 *   capture  --oracle URL --oracle-user U --oracle-password P --as-of YYYY-MM-DD --out views-baseline.csv
 *   capture  --pg URL --pg-user U --pg-password P --as-of YYYY-MM-DD --out views-baseline.csv
 *            (golden-oracle mode OFF: records the PostgreSQL pack; see tests/golden/README.md)
 *   compare  --pg URL --pg-user U --pg-password P --as-of YYYY-MM-DD --baseline views-baseline.csv --report out.md
 *   live     both sets of connection args; compares Oracle and PostgreSQL directly without a CSV
 * </pre>
 *
 * Exit code 0 = reconciled, 1 = differences, 2 = usage/connection error.
 */
public final class ReconcileMain {

  private ReconcileMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      System.exit(2);
    }
    Map<String, String> o = parse(args);
    Path root = queriesRoot(o.get("--queries"));
    LocalDate asOf = LocalDate.parse(o.getOrDefault("--as-of", LocalDate.now().toString()));
    List<ViewQuery> queries = ViewQuery.all(root);
    switch (args[0]) {
      case "capture" -> {
        boolean oracle = o.containsKey("--oracle");
        List<Cell> cells = capture(connect(o, oracle ? "--oracle" : "--pg"), queries, asOf, oracle);
        Baseline.write(Path.of(o.get("--out")), cells);
        System.out.println(
            "captured "
                + cells.size()
                + " cells from "
                + (oracle
                    ? "Oracle"
                    : "PostgreSQL (golden-oracle mode OFF; Oracle leg untested-live)"));
      }
      case "compare" -> {
        List<Cell> expected = Baseline.read(Path.of(o.get("--baseline")));
        List<Cell> actual = capture(connect(o, "--pg"), queries, asOf, false);
        exit(report(Diff.of(expected, actual), o));
      }
      case "live" -> {
        List<Cell> expected = capture(connect(o, "--oracle"), queries, asOf, true);
        List<Cell> actual = capture(connect(o, "--pg"), queries, asOf, false);
        exit(report(Diff.of(expected, actual), o));
      }
      default -> {
        usage();
        System.exit(2);
      }
    }
  }

  static List<Cell> capture(Connection c, List<ViewQuery> queries, LocalDate asOf, boolean oracle)
      throws SQLException {
    List<Cell> out = new ArrayList<>();
    try (c) {
      for (ViewQuery q : queries) {
        NamedSql sql = new NamedSql(oracle ? q.oracleText() : q.pgText());
        try (PreparedStatement ps = sql.prepare(c, asOf);
            ResultSet rs = ps.executeQuery()) {
          out.addAll(Canonical.read(q.view(), rs));
        }
      }
    }
    return out;
  }

  private static Diff report(Diff diff, Map<String, String> o) throws IOException {
    String md = diff.toMarkdown();
    if (o.containsKey("--report")) {
      Files.writeString(Path.of(o.get("--report")), md);
    }
    System.out.println(md);
    return diff;
  }

  private static void exit(Diff diff) {
    System.exit(diff.reconciled() ? 0 : 1);
  }

  private static Connection connect(Map<String, String> o, String prefix) throws SQLException {
    String url = o.get(prefix);
    if (url == null) {
      throw new IllegalArgumentException(prefix + " URL is required");
    }
    return DriverManager.getConnection(url, o.get(prefix + "-user"), o.get(prefix + "-password"));
  }

  static final String DEFAULT_QUERIES = "tests/reconciliation";

  /**
   * Locates the reconciliation query pack. An explicit {@code --queries} wins; otherwise {@code
   * tests/reconciliation} is searched upwards from the working directory (repo root,
   * tools/reconcile, ...) and then from the directory holding this jar, so the documented {@code
   * java -jar target/hrms-tool-reconcile.jar} works from any checkout directory.
   */
  static Path queriesRoot(String explicit) {
    if (explicit != null) {
      return Path.of(explicit);
    }
    return resolveQueries(Path.of("").toAbsolutePath(), jarDirectory());
  }

  static Path resolveQueries(Path cwd, Path jarDir) {
    for (Path start : new Path[] {cwd, jarDir}) {
      for (Path dir = start; dir != null; dir = dir.getParent()) {
        Path candidate = dir.resolve(DEFAULT_QUERIES);
        if (Files.isDirectory(candidate.resolve("pg"))) {
          return candidate;
        }
      }
    }
    throw new IllegalArgumentException(
        DEFAULT_QUERIES
            + " not found above "
            + cwd
            + (jarDir == null ? "" : " or " + jarDir)
            + "; pass --queries <dir>");
  }

  private static Path jarDirectory() {
    try {
      Path location =
          Path.of(ReconcileMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      return Files.isDirectory(location) ? location : location.getParent();
    } catch (Exception e) {
      return null;
    }
  }

  static Map<String, String> parse(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (int i = 1; i + 1 < args.length; i += 2) {
      m.put(args[i], args[i + 1]);
    }
    return m;
  }

  private static void usage() {
    System.err.println(
        "usage: reconcile (capture|compare|live) [--queries DIR (default: tests/reconciliation found"
            + " above the working directory or the jar)] --as-of DATE"
            + " [--oracle URL --oracle-user U --oracle-password P]"
            + " [--pg URL --pg-user U --pg-password P] [--out CSV] [--baseline CSV] [--report MD]");
  }
}
