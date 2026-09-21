package com.acme.hrms.tools.reconcile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One reconciliation pair: the Oracle view and its PostgreSQL equivalent under
 * tests/reconciliation.
 */
public record ViewQuery(String view, Path oracleSql, Path pgSql) {

  public static final List<String> VIEWS =
      List.of(
          "VW_ACTIVE_EMPLOYEES",
          "VW_ORG_HIERARCHY",
          "VW_EMPLOYEE_COMPENSATION",
          "VW_LEAVE_SUMMARY",
          "VW_PAYROLL_LATEST",
          "VW_PENDING_APPROVALS");

  public static List<ViewQuery> all(Path reconciliationRoot) {
    return VIEWS.stream()
        .map(
            v -> {
              String file = v.toLowerCase() + ".sql";
              return new ViewQuery(
                  v,
                  reconciliationRoot.resolve("oracle").resolve(file),
                  reconciliationRoot.resolve("pg").resolve(file));
            })
        .toList();
  }

  public String oracleText() {
    return read(oracleSql);
  }

  public String pgText() {
    return read(pgSql);
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
