package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Markdown + exit-code report over the per-scenario legacy/target/expected outcomes. */
public final class DiffReport {

  /**
   * @param legacySource provenance of the legacy column when no Oracle ran: {@code recorded} (from
   *     the PL/SQL reference) or {@code untested-live} (needs a live Oracle Forms session)
   */
  public record Row(
      String scenario,
      Outcome expected,
      Outcome legacyExpected,
      Outcome legacy,
      Outcome target,
      String legacySource) {
    public Row(
        String scenario, Outcome expected, Outcome legacyExpected, Outcome legacy, Outcome target) {
      this(scenario, expected, legacyExpected, legacy, target, ScenarioRegistry.RECORDED);
    }

    boolean targetOk() {
      return Objects.equals(expected, target);
    }

    boolean legacyOk() {
      return legacy == null || Objects.equals(legacyExpected, legacy);
    }

    String verdict() {
      if (targetOk() && legacyOk()) {
        return "PASS";
      }
      if (!targetOk() && legacyOk()) {
        return "TARGET-DIFF";
      }
      if (targetOk()) {
        return "ORACLE-DRIFT";
      }
      return "BOTH-DIFF";
    }
  }

  private final List<Row> rows = new ArrayList<>();

  public void add(Row r) {
    rows.add(r);
  }

  public List<Row> rows() {
    return rows;
  }

  public boolean passed() {
    return rows.stream().allMatch(r -> r.targetOk() && r.legacyOk());
  }

  public String toMarkdown() {
    StringBuilder sb = new StringBuilder("# Parallel run – Level 2\n\n");
    sb.append("Result: **")
        .append(passed() ? "PASS" : "FAIL")
        .append("** (")
        .append(rows.stream().filter(r -> r.verdict().equals("PASS")).count())
        .append('/')
        .append(rows.size())
        .append(" scenarios)\n\n");
    sb.append(
        "| scenario | expected (contract) | legacy | target | verdict |\n|---|---|---|---|---|\n");
    for (Row r : rows) {
      sb.append("| ")
          .append(r.scenario())
          .append(" | ")
          .append(fmt(r.expected()))
          .append(" | ")
          .append(r.legacy() == null ? "n/a (" + r.legacySource() + ")" : fmt(r.legacy()))
          .append(
              Objects.equals(r.expected(), r.legacyExpected())
                  ? ""
                  : " (documented divergence, expects " + fmt(r.legacyExpected()) + ")")
          .append(" | ")
          .append(fmt(r.target()))
          .append(" | ")
          .append(r.verdict())
          .append(" |\n");
    }
    return sb.toString();
  }

  static String fmt(Outcome o) {
    if (o == null) {
      return "error";
    }
    return o.errorCode() != null ? "`" + o.errorCode() + "`" : "ok " + o.fields();
  }
}
