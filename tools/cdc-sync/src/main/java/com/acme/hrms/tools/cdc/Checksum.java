package com.acme.hrms.tools.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Row count + per-column checksum for a table, computed identically on both sides so bulk load and
 * reverse extract can be verified (CUTOVER_PLAN.md §2 rule 6: "row counts and per-column checksums
 * must match"). Checksum = SUM over rows of a stable hash of the canonical text of the column; the
 * canonical text is produced by the same JDBC normalisation used by tools/reconcile.
 */
public final class Checksum {

  public record TableChecksum(String table, long rows, Map<String, Long> columns) {}

  private Checksum() {}

  public static TableChecksum compute(Connection c, String table, boolean oracle)
      throws SQLException {
    String qualified =
        oracle ? TypeMapping.oracleIdentifier(table) : TypeMapping.pgIdentifier(table);
    String pk = TableGroup.PRIMARY_KEYS.get(table);
    String order = pk == null ? "" : " order by " + (oracle ? pk.toUpperCase() : pk);
    Map<String, Long> sums = new LinkedHashMap<>();
    long rows = 0;
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select * from " + qualified + order)) {
      int cols = rs.getMetaData().getColumnCount();
      String[] names = new String[cols];
      for (int i = 1; i <= cols; i++) {
        names[i - 1] = rs.getMetaData().getColumnLabel(i).toLowerCase();
        sums.put(names[i - 1], 0L);
      }
      while (rs.next()) {
        rows++;
        for (int i = 1; i <= cols; i++) {
          Object v = rs.getObject(i);
          String text = canonical(v);
          sums.merge(names[i - 1], hash(text), Long::sum);
        }
      }
    }
    return new TableChecksum(table, rows, sums);
  }

  static String canonical(Object v) {
    if (v == null) {
      return "\\N";
    }
    if (v instanceof java.math.BigDecimal d) {
      java.math.BigDecimal s = d.stripTrailingZeros();
      return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
    if (v instanceof Number n) {
      return n.toString();
    }
    if (v instanceof java.sql.Timestamp ts) {
      java.time.LocalDateTime t = ts.toLocalDateTime();
      return t.toLocalTime().toSecondOfDay() == 0
          ? t.toLocalDate().toString()
          : t.withNano(0).toString();
    }
    if (v instanceof java.sql.Date d) {
      return d.toLocalDate().toString();
    }
    if (v instanceof Boolean b) {
      return b ? "Y" : "N";
    }
    return v.toString().stripTrailing();
  }

  /** FNV-1a 64-bit; stable across JVMs (String.hashCode is 32-bit and collision-prone in sums). */
  static long hash(String s) {
    long h = 0xcbf29ce484222325L;
    for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      h ^= (b & 0xff);
      h *= 0x100000001b3L;
    }
    return h;
  }
}
