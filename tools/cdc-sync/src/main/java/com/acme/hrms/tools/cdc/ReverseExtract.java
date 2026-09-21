package com.acme.hrms.tools.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Step (iv) of CUTOVER_PLAN.md §2 rule 6 – the rollback job: PostgreSQL → Oracle, plain JDBC batch
 * keyed on primary key (MERGE), type mapping inverted. Only rows modified since the flip ({@code
 * since}) are shipped when the table has a modified_date/created_date column; otherwise the whole
 * table. In "dry-run" mode (nightly rehearsal against a copy of Oracle) the connection is rolled
 * back at the end instead of committed.
 */
public final class ReverseExtract {

  private static final int BATCH = 500;

  private final Connection pg;
  private final Connection oracle;

  public ReverseExtract(Connection pg, Connection oracle) {
    this.pg = pg;
    this.oracle = oracle;
  }

  public long run(TableGroup group, Instant since, boolean dryRun) throws SQLException {
    oracle.setAutoCommit(false);
    long total = 0;
    try {
      for (String table : group.tables()) {
        total += extractTable(table, since);
      }
      if (dryRun) {
        oracle.rollback();
      } else {
        oracle.commit();
      }
    } catch (SQLException e) {
      oracle.rollback();
      throw e;
    }
    return total;
  }

  long extractTable(String table, Instant since) throws SQLException {
    String where = since == null ? "" : changedSincePredicate(table);
    String sql = "select * from " + table + where;
    long n = 0;
    try (PreparedStatement sel = pg.prepareStatement(sql)) {
      if (!where.isEmpty()) {
        sel.setTimestamp(1, java.sql.Timestamp.from(since));
        sel.setTimestamp(2, java.sql.Timestamp.from(since));
      }
      try (ResultSet rs = sel.executeQuery()) {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<String> oracleCols = new ArrayList<>();
        for (int i = 1; i <= cols; i++) {
          oracleCols.add(TypeMapping.oracleIdentifier(md.getColumnLabel(i)));
        }
        try (PreparedStatement merge = oracle.prepareStatement(mergeSql(table, oracleCols))) {
          int inBatch = 0;
          while (rs.next()) {
            for (int i = 1; i <= cols; i++) {
              merge.setObject(i, TypeMapping.toOracle(rs.getObject(i)));
            }
            merge.addBatch();
            n++;
            if (++inBatch == BATCH) {
              merge.executeBatch();
              inBatch = 0;
            }
          }
          if (inBatch > 0) {
            merge.executeBatch();
          }
        }
      }
    }
    return n;
  }

  private String changedSincePredicate(String table) throws SQLException {
    boolean hasModified = hasColumn(table, "modified_date");
    boolean hasCreated = hasColumn(table, "created_date");
    if (hasModified && hasCreated) {
      return " where coalesce(modified_date, created_date) >= ? or created_date >= ?";
    }
    if (hasCreated) {
      return " where created_date >= ? and created_date >= ?";
    }
    return "";
  }

  private boolean hasColumn(String table, String column) throws SQLException {
    try (PreparedStatement ps =
        pg.prepareStatement(
            "select 1 from information_schema.columns where table_name = ? and column_name = ?")) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  static String mergeSql(String table, List<String> cols) {
    String pk = TableGroup.PRIMARY_KEYS.get(table).toUpperCase();
    String target = TypeMapping.oracleIdentifier(table);
    StringBuilder sb = new StringBuilder("merge into ").append(target).append(" t using (select ");
    for (int i = 0; i < cols.size(); i++) {
      sb.append(i > 0 ? ", " : "").append("? as ").append(cols.get(i));
    }
    sb.append(" from dual) s on (t.").append(pk).append(" = s.").append(pk).append(")");
    sb.append(" when matched then update set ");
    sb.append(
        String.join(
            ", ",
            cols.stream().filter(c -> !c.equals(pk)).map(c -> "t." + c + " = s." + c).toList()));
    sb.append(" when not matched then insert (")
        .append(String.join(", ", cols))
        .append(") values (");
    sb.append(String.join(", ", cols.stream().map(c -> "s." + c).toList())).append(")");
    return sb.toString();
  }

  /** Oracle-side helper for the round-trip smoke test: count rows in the mirrored table. */
  public static long count(Connection c, String table) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
