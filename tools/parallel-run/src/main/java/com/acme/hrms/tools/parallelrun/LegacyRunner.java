package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a scenario's legacy PL/SQL block on Oracle and projects the result to an {@link
 * Outcome}. ORA-20xxx application errors become the legacy code string ({@code "-20301"}). Each
 * scenario runs in its own transaction which is rolled back so the golden seed stays frozen.
 *
 * <p>Also able to run a whole utPLSQL suite ({@link #runUtPlsql}) and return its JUnit XML for the
 * diff report – this is how the Phase-0 characterization suites are executed by the integration
 * session.
 */
public final class LegacyRunner {

  private static final Pattern ORA = Pattern.compile("ORA-(20\\d{3})");
  private static final Pattern BIND = Pattern.compile(":([a-z_]+)");

  private final Connection oracle;
  private final Map<String, Object> context;

  public LegacyRunner(Connection oracle, Map<String, Object> context) {
    this.oracle = oracle;
    this.context = context;
  }

  public Outcome run(Scenario s) throws SQLException {
    if (s.legacy() == null) {
      return null;
    }
    oracle.setAutoCommit(false);
    try {
      Map<String, String> out = new LinkedHashMap<>();
      java.util.List<String> order = new java.util.ArrayList<>();
      String sql =
          BIND.matcher(s.legacy().plsql())
              .replaceAll(
                  m -> {
                    order.add(m.group(1));
                    return "?";
                  });
      try (CallableStatement cs = oracle.prepareCall(sql)) {
        for (int i = 0; i < order.size(); i++) {
          String name = order.get(i);
          if (s.legacy().outBinds().contains(name)) {
            cs.registerOutParameter(i + 1, Types.VARCHAR);
          } else {
            cs.setObject(i + 1, context.get(name));
          }
        }
        cs.execute();
        for (int i = 0; i < order.size(); i++) {
          if (s.legacy().outBinds().contains(order.get(i))) {
            out.put(order.get(i), cs.getString(i + 1));
          }
        }
      }
      return Outcome.ok(out);
    } catch (SQLException e) {
      Matcher m = ORA.matcher(e.getMessage() == null ? "" : e.getMessage());
      if (m.find()) {
        return Outcome.error("-" + m.group(1));
      }
      throw e;
    } finally {
      oracle.rollback();
    }
  }

  /** Runs {@code ut.run(suitePath)} with the JUnit reporter and returns the XML document. */
  public String runUtPlsql(String suitePath) throws SQLException {
    String block =
        "declare l_lines ut_varchar2_list; begin "
            + "select * bulk collect into l_lines from table(ut.run(?, ut_junit_reporter())); "
            + ":out := ''; for i in 1..l_lines.count loop :out := :out || l_lines(i) || chr(10); end loop; end;";
    try (CallableStatement cs = oracle.prepareCall(block.replace(":out", "?"))) {
      cs.setString(1, suitePath);
      cs.registerOutParameter(2, Types.CLOB);
      cs.registerOutParameter(3, Types.CLOB);
      cs.registerOutParameter(4, Types.CLOB);
      cs.execute();
      return cs.getString(2);
    }
  }
}
