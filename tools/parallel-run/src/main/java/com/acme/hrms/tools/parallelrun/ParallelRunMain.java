package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Level-2 harness (TEST_STRATEGY.md §2.2, §7). Usage:
 *
 * <pre>
 *   parallel-run [--only auth,sso] --target http://proxy:8080 --seed-password P
 *                [--oracle jdbc:oracle:thin:@//host:1521/HRMSPDB --oracle-user U --oracle-password P]
 *                [--utplsql hrms:ut_pkg_security,hrms:ut_pkg_employee] [--report target/parallel-run.md]
 *                [--flags payroll=NEW,payroll.engine=JAVA]   (default: HRMS_FLAG_* of the environment)
 * </pre>
 *
 * Without {@code --oracle} only the target side is executed and compared to the contract (that is
 * what the backend build does) and untested-live scenarios (P0-D1) expect the contracted no-Oracle
 * outcome, reported as {@code UNTESTED-LIVE}; with it, every scenario also runs on the golden
 * oracle and utPLSQL suites' JUnit XML is written next to the report. Exit 0 = every row PASS or
 * UNTESTED-LIVE; 1 = differences; 2 = usage/connection error.
 */
public final class ParallelRunMain {

  private ParallelRunMain() {}

  public static void main(String[] args) throws Exception {
    Map<String, String> o = new HashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      o.put(args[i], args[i + 1]);
    }
    if (!o.containsKey("--target")) {
      System.err.println("usage: parallel-run --target URL --seed-password P [--oracle URL ...]");
      System.exit(2);
    }
    TargetFlags flags = TargetFlags.fromEnv();
    if (o.containsKey("--flags")) {
      flags = flags.with(o.get("--flags"));
    }
    List<Scenario> scenarios = ScenarioRegistry.all(flags);
    if (o.containsKey("--only")) {
      List<String> mods = List.of(o.get("--only").split(","));
      scenarios = scenarios.stream().filter(s -> mods.contains(s.module())).toList();
    }
    RestRunner rest =
        new RestRunner(
            o.get("--target"), o.getOrDefault("--seed-password", ScenarioRegistry.PASSWORD));
    DiffReport report = new DiffReport();
    Connection oracle = null;
    try {
      if (o.containsKey("--oracle")) {
        oracle =
            DriverManager.getConnection(
                o.get("--oracle"), o.get("--oracle-user"), o.get("--oracle-password"));
      }
      Map<String, Object> ctx =
          Map.of(
              "username",
              ScenarioRegistry.USER,
              "password",
              o.getOrDefault("--seed-password", ScenarioRegistry.PASSWORD),
              "emp_id",
              2,
              "new_password",
              "",
              "user",
              PerformanceScenarios.ADMIN);
      LegacyRunner legacy = oracle == null ? null : new LegacyRunner(oracle, ctx);
      for (Scenario s : scenarios) {
        Outcome target;
        try {
          target = rest.run(s);
        } catch (Exception e) {
          target = null;
        }
        Outcome leg = null;
        if (legacy != null) {
          try {
            leg = legacy.run(s);
          } catch (Exception e) {
            leg = null;
          }
        }
        report.add(
            new DiffReport.Row(
                s.id(),
                ScenarioRegistry.targetExpect(s, legacy != null),
                ScenarioRegistry.legacyOutcome(s),
                leg,
                target,
                ScenarioRegistry.legacySource(s)));
      }
      Path out = Path.of(o.getOrDefault("--report", "target/parallel-run.md"));
      Files.createDirectories(out.toAbsolutePath().getParent());
      Files.writeString(out, report.toMarkdown());
      if (legacy != null && o.containsKey("--utplsql")) {
        for (String suite : o.get("--utplsql").split(",")) {
          Files.writeString(
              out.resolveSibling(suite.replace(':', '_') + ".xml"), legacy.runUtPlsql(suite));
        }
      }
      System.out.print(report.toMarkdown());
      System.exit(report.passed() ? 0 : 1);
    } finally {
      if (oracle != null) {
        oracle.close();
      }
    }
  }
}
