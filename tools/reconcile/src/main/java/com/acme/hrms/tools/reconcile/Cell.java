package com.acme.hrms.tools.reconcile;

/** Long-form baseline row: one value of one column of one result row of one view. */
public record Cell(String view, int rowNo, String column, String value) {

  public static final String NULL = "\\N";

  public String toCsv() {
    return String.join(",", view, Integer.toString(rowNo), column, escape(value));
  }

  static String escape(String v) {
    if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0) {
      return v;
    }
    return '"' + v.replace("\"", "\"\"") + '"';
  }
}
