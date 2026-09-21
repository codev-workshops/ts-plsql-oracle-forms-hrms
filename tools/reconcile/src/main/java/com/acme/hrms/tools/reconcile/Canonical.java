package com.acme.hrms.tools.reconcile;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalises JDBC values so Oracle and PostgreSQL output compare byte-for-byte: numbers via
 * BigDecimal with trailing zeros stripped, DATE/TIMESTAMP as ISO local date-time (DATE columns that
 * carry midnight collapse to the date), CHAR padding trimmed, column names upper-cased.
 */
public final class Canonical {

  private Canonical() {}

  public static List<Cell> read(String view, ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int cols = md.getColumnCount();
    List<Cell> out = new ArrayList<>();
    int row = 0;
    while (rs.next()) {
      row++;
      for (int i = 1; i <= cols; i++) {
        out.add(new Cell(view, row, md.getColumnLabel(i).toUpperCase(), value(rs, md, i)));
      }
    }
    return out;
  }

  static String value(ResultSet rs, ResultSetMetaData md, int i) throws SQLException {
    int type = md.getColumnType(i);
    switch (type) {
      case Types.NUMERIC,
          Types.DECIMAL,
          Types.INTEGER,
          Types.BIGINT,
          Types.SMALLINT,
          Types.TINYINT,
          Types.DOUBLE,
          Types.FLOAT,
          Types.REAL -> {
        BigDecimal d = rs.getBigDecimal(i);
        return d == null ? Cell.NULL : number(d);
      }
      case Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
        Timestamp ts = rs.getTimestamp(i);
        return ts == null ? Cell.NULL : dateTime(ts.toLocalDateTime());
      }
      default -> {
        String s = rs.getString(i);
        if (s == null) {
          return Cell.NULL;
        }
        return type == Types.CHAR ? s.stripTrailing() : s;
      }
    }
  }

  public static String number(BigDecimal d) {
    BigDecimal s = d.stripTrailingZeros();
    if (s.scale() < 0) {
      s = s.setScale(0);
    }
    return s.toPlainString();
  }

  public static String dateTime(LocalDateTime t) {
    if (t.toLocalTime().toSecondOfDay() == 0 && t.getNano() == 0) {
      return LocalDate.from(t).toString();
    }
    return t.withNano(0).toString();
  }
}
