package com.acme.hrms.tools.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Step (i) of CUTOVER_PLAN.md §2 rule 6: consistent AS OF SCN snapshot of a table group from
 * Oracle, transformed per {@link TypeMapping}, loaded into PostgreSQL by keyed upsert. Idempotent:
 * rerunning converges on the same rows. Sequences are advanced past MAX(pk) afterwards so the Java
 * modules can take over numbering after the flip.
 */
public final class BulkLoader {

  private static final int BATCH = 500;

  private final Connection oracle;
  private final Connection pg;

  public BulkLoader(Connection oracle, Connection pg) {
    this.oracle = oracle;
    this.pg = pg;
  }

  public long currentScn() throws SQLException {
    try (Statement st = oracle.createStatement();
        ResultSet rs = st.executeQuery("select current_scn from v$database")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  public long load(TableGroup group, long scn) throws SQLException {
    long total = 0;
    pg.setAutoCommit(false);
    for (String table : group.tables()) {
      total += loadTable(table, scn);
    }
    pg.commit();
    return total;
  }

  long loadTable(String table, long scn) throws SQLException {
    String src = TypeMapping.oracleIdentifier(table);
    String sql = "select * from " + src + (scn > 0 ? " as of scn " + scn : "");
    long n = 0;
    try (Statement st = oracle.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      ResultSetMetaData md = rs.getMetaData();
      int cols = md.getColumnCount();
      String[] pgCols = new String[cols];
      int[] types = new int[cols];
      for (int i = 1; i <= cols; i++) {
        pgCols[i - 1] = TypeMapping.pgIdentifier(md.getColumnLabel(i));
        types[i - 1] = md.getColumnType(i);
      }
      String upsert = upsertSql(table, List.of(pgCols));
      try (PreparedStatement ps = pg.prepareStatement(upsert)) {
        int inBatch = 0;
        while (rs.next()) {
          for (int i = 1; i <= cols; i++) {
            ps.setObject(i, TypeMapping.toPostgres(rs.getObject(i), types[i - 1]));
          }
          ps.addBatch();
          n++;
          if (++inBatch == BATCH) {
            ps.executeBatch();
            inBatch = 0;
          }
        }
        if (inBatch > 0) {
          ps.executeBatch();
        }
      }
    }
    advanceSequence(table);
    return n;
  }

  static String upsertSql(String table, List<String> cols) {
    String pk = TableGroup.PRIMARY_KEYS.get(table);
    String colList = String.join(", ", cols);
    String placeholders = String.join(", ", cols.stream().map(c -> "?").toList());
    String updates =
        String.join(
            ", ",
            cols.stream().filter(c -> !c.equals(pk)).map(c -> c + " = excluded." + c).toList());
    return "insert into "
        + table
        + " ("
        + colList
        + ") values ("
        + placeholders
        + ") on conflict ("
        + pk
        + ") do update set "
        + updates;
  }

  private void advanceSequence(String table) throws SQLException {
    restartSequence(pg, table);
  }

  /**
   * Restarts the table's sequence at MAX(pk) + 1 (CUTOVER_PLAN.md §2 rule 6 / §5.3) so the first
   * Java insert after the flip cannot collide with a row loaded from Oracle. No-op for tables
   * without a sequence. Returns the value the next {@code nextval} will yield.
   */
  static long restartSequence(Connection pg, String table) throws SQLException {
    String pk = TableGroup.PRIMARY_KEYS.get(table);
    String seq = TableGroup.SEQUENCES.get(table);
    if (seq == null) {
      return -1;
    }
    try (PreparedStatement ps =
        pg.prepareStatement(
            "select setval(?, coalesce((select max("
                + pk
                + ") from "
                + table
                + "), 0) + 1, false)")) {
      ps.setString(1, seq);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
