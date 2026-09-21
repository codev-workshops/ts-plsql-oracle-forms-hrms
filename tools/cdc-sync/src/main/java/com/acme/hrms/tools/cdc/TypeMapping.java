package com.acme.hrms.tools.cdc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * MODERNIZATION_BLUEPRINT.md §10 value rules, applied symmetrically: Oracle → PostgreSQL on
 * load/CDC and inverted on the reverse extract. Identifiers: upper-case unquoted on Oracle,
 * lower-case unquoted on PostgreSQL. Empty strings do not exist on Oracle (they are NULL) – on the
 * way back a PostgreSQL '' becomes NULL, on the way in nothing changes.
 */
public final class TypeMapping {

  private TypeMapping() {}

  public static String pgIdentifier(String oracleName) {
    return oracleName.toLowerCase();
  }

  public static String oracleIdentifier(String pgName) {
    return pgName.toUpperCase();
  }

  /** Value as read from Oracle, prepared for PostgreSQL. */
  public static Object toPostgres(Object v, int jdbcType) {
    if (v == null) {
      return null;
    }
    return switch (jdbcType) {
      case Types.NUMERIC, Types.DECIMAL -> {
        BigDecimal d = (BigDecimal) v;
        yield d.scale() <= 0 && d.abs().compareTo(new BigDecimal("9223372036854775807")) <= 0
            ? (Object) d.longValueExact()
            : d;
      }
      case Types.TIMESTAMP, Types.DATE ->
          v instanceof Timestamp ts ? ts : Timestamp.valueOf(v.toString());
      case Types.CHAR -> ((String) v).stripTrailing();
      default -> v;
    };
  }

  /** Value as read from PostgreSQL, prepared for Oracle. */
  public static Object toOracle(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof String s && s.isEmpty()) {
      return null;
    }
    if (v instanceof Boolean b) {
      return b ? "Y" : "N";
    }
    return v;
  }
}
