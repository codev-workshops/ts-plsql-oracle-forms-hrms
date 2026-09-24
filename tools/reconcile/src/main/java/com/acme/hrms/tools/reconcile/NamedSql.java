package com.acme.hrms.tools.reconcile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal `:name` bind support so the same SQL text runs on both JDBC drivers. */
public final class NamedSql {

  private static final Pattern BIND = Pattern.compile("(?<![:\\w]):([a-zA-Z_][a-zA-Z0-9_]*)");

  private final String jdbcSql;
  private final List<String> names = new ArrayList<>();

  public NamedSql(String sql) {
    String stripped = stripComments(sql).trim();
    if (stripped.endsWith(";")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    Matcher m = BIND.matcher(stripped);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      names.add(m.group(1));
      m.appendReplacement(sb, "?");
    }
    m.appendTail(sb);
    this.jdbcSql = sb.toString();
  }

  static String stripComments(String sql) {
    return sql.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
  }

  public String jdbcSql() {
    return jdbcSql;
  }

  public List<String> names() {
    return List.copyOf(names);
  }

  public PreparedStatement prepare(Connection c, LocalDate asOf) throws SQLException {
    PreparedStatement ps = c.prepareStatement(jdbcSql);
    for (int i = 0; i < names.size(); i++) {
      if (!names.get(i).equals("as_of")) {
        throw new IllegalArgumentException("unknown bind :" + names.get(i));
      }
      ps.setTimestamp(i + 1, Timestamp.valueOf(asOf.atStartOfDay()));
    }
    return ps;
  }
}
