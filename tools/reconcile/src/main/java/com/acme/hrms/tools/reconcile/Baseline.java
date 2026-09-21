package com.acme.hrms.tools.reconcile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads/writes tests/golden/views-baseline.csv (long form: view,row_no,column,value). */
public final class Baseline {

  public static final String HEADER = "view,row_no,column,value";

  private Baseline() {}

  public static void write(Path file, List<Cell> cells) throws IOException {
    List<String> lines = new ArrayList<>(cells.size() + 1);
    lines.add(HEADER);
    cells.forEach(c -> lines.add(c.toCsv()));
    Files.write(file, lines);
  }

  public static List<Cell> read(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file);
    if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
      throw new IllegalArgumentException("not a views-baseline.csv: " + file);
    }
    List<Cell> out = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      String l = lines.get(i);
      if (l.isBlank()) {
        continue;
      }
      out.add(parse(l));
    }
    return out;
  }

  static Cell parse(String line) {
    String[] head = line.split(",", 4);
    if (head.length < 4) {
      throw new IllegalArgumentException("bad baseline line: " + line);
    }
    return new Cell(head[0], Integer.parseInt(head[1]), head[2], unescape(head[3]));
  }

  static String unescape(String v) {
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      return v.substring(1, v.length() - 1).replace("\"\"", "\"");
    }
    return v;
  }
}
