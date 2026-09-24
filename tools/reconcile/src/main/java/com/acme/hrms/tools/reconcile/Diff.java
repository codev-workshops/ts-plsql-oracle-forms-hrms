package com.acme.hrms.tools.reconcile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Cell-wise comparison of two long-form captures; empty {@link #mismatches()} == reconciled. */
public final class Diff {

  public record Mismatch(String view, int rowNo, String column, String expected, String actual) {}

  private final List<Mismatch> mismatches = new ArrayList<>();
  private final Map<String, int[]> rowCounts = new LinkedHashMap<>();

  public static Diff of(List<Cell> expected, List<Cell> actual) {
    Diff d = new Diff();
    Map<String, String> exp = index(expected);
    Map<String, String> act = index(actual);
    for (String key : new TreeSet<>(union(exp.keySet(), act.keySet()))) {
      String e = exp.get(key);
      String a = act.get(key);
      if (!Objects.equals(e, a)) {
        String[] k = key.split("\u0000");
        d.mismatches.add(new Mismatch(k[0], Integer.parseInt(k[1]), k[2], e, a));
      }
    }
    for (String v : ViewQuery.VIEWS) {
      d.rowCounts.put(v, new int[] {rows(expected, v), rows(actual, v)});
    }
    return d;
  }

  private static Map<String, String> index(List<Cell> cells) {
    Map<String, String> m = new LinkedHashMap<>();
    cells.forEach(c -> m.put(c.view() + "\u0000" + c.rowNo() + "\u0000" + c.column(), c.value()));
    return m;
  }

  private static <T> List<T> union(java.util.Set<T> a, java.util.Set<T> b) {
    List<T> l = new ArrayList<>(a);
    b.stream().filter(x -> !a.contains(x)).forEach(l::add);
    return l;
  }

  private static int rows(List<Cell> cells, String view) {
    return cells.stream().filter(c -> c.view().equals(view)).mapToInt(Cell::rowNo).max().orElse(0);
  }

  public List<Mismatch> mismatches() {
    return List.copyOf(mismatches);
  }

  public boolean reconciled() {
    return mismatches.isEmpty();
  }

  public String toMarkdown() {
    StringBuilder sb = new StringBuilder("# View reconciliation report\n\n");
    sb.append("| view | baseline rows | actual rows | mismatching cells |\n|---|---:|---:|---:|\n");
    rowCounts.forEach(
        (v, c) ->
            sb.append("| ")
                .append(v)
                .append(" | ")
                .append(c[0])
                .append(" | ")
                .append(c[1])
                .append(" | ")
                .append(mismatches.stream().filter(m -> m.view().equals(v)).count())
                .append(" |\n"));
    sb.append("\nResult: **").append(reconciled() ? "RECONCILED" : "DIFF").append("**\n");
    if (!reconciled()) {
      sb.append("\n| view | row | column | baseline | actual |\n|---|---:|---|---|---|\n");
      mismatches.stream()
          .limit(500)
          .forEach(
              m ->
                  sb.append("| ")
                      .append(m.view())
                      .append(" | ")
                      .append(m.rowNo())
                      .append(" | ")
                      .append(m.column())
                      .append(" | ")
                      .append(m.expected() == null ? "*(absent)*" : m.expected())
                      .append(" | ")
                      .append(m.actual() == null ? "*(absent)*" : m.actual())
                      .append(" |\n"));
    }
    return sb.toString();
  }
}
