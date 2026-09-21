package com.acme.hrms.tools.cdc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * tools/cdc-sync entry point (CUTOVER_PLAN.md §2 rule 6, §4.2 item 0.5b).
 *
 * <pre>
 *   bulk-load --group reference|employee|payroll|leave|performance --oracle URL ... --pg URL ...
 *   checksum  --group G --oracle URL ... --pg URL ...           (exit 1 on mismatch)
 *   reverse   --group G --since 2024-06-30T00:00:00Z [--apply]  --pg URL ... --oracle URL ...
 * </pre>
 *
 * Continuous CDC during the bake is Debezium's Oracle connector (LogMiner) – see
 * tools/cdc-sync/debezium/ for the connector config; this CLI covers the bulk snapshot, the
 * checksum gate and the reverse extract that Debezium does not.
 */
public final class CdcSyncMain {

  private CdcSyncMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      System.exit(2);
    }
    Map<String, String> o = new HashMap<>();
    for (int i = 1; i + 1 < args.length; i += 2) {
      o.put(args[i], args[i + 1]);
    }
    boolean apply = java.util.Arrays.asList(args).contains("--apply");
    TableGroup group = TableGroup.byFlag(o.getOrDefault("--group", "reference"));
    try (Connection oracle = connect(o, "--oracle");
        Connection pg = connect(o, "--pg")) {
      switch (args[0]) {
        case "bulk-load" -> {
          BulkLoader loader = new BulkLoader(oracle, pg);
          long scn = loader.currentScn();
          long rows = loader.load(group, scn);
          System.out.println("loaded " + rows + " rows of " + group + " as of scn " + scn);
          System.exit(verify(oracle, pg, group) ? 0 : 1);
        }
        case "checksum" -> System.exit(verify(oracle, pg, group) ? 0 : 1);
        case "reverse" -> {
          Instant since = o.containsKey("--since") ? Instant.parse(o.get("--since")) : null;
          long rows = new ReverseExtract(pg, oracle).run(group, since, !apply);
          System.out.println(
              (apply ? "applied " : "dry-run: would apply ") + rows + " rows to Oracle");
        }
        default -> {
          usage();
          System.exit(2);
        }
      }
    }
  }

  static boolean verify(Connection oracle, Connection pg, TableGroup group) throws Exception {
    boolean ok = true;
    for (String t : group.tables()) {
      Checksum.TableChecksum a = Checksum.compute(oracle, t, true);
      Checksum.TableChecksum b = Checksum.compute(pg, t, false);
      boolean same = a.rows() == b.rows() && a.columns().equals(b.columns());
      System.out.printf(
          "%-28s oracle=%d pg=%d %s%n", t, a.rows(), b.rows(), same ? "OK" : "MISMATCH");
      ok &= same;
    }
    return ok;
  }

  private static Connection connect(Map<String, String> o, String prefix) throws Exception {
    return DriverManager.getConnection(
        o.get(prefix), o.get(prefix + "-user"), o.get(prefix + "-password"));
  }

  private static void usage() {
    System.err.println(
        "usage: cdc-sync (bulk-load|checksum|reverse) --group G --oracle URL --oracle-user U"
            + " --oracle-password P --pg URL --pg-user U --pg-password P [--since ISO] [--apply]");
  }
}
