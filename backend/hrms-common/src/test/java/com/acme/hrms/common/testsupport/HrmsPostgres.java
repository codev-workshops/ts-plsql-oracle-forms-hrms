package com.acme.hrms.common.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers PostgreSQL for every Level-1 repository test. One container per JVM; each
 * test class gets a fresh schema via {@link #resetSchema()} (Flyway clean + migrate) and may load
 * the frozen fixtures from tools/fixtures/pg.
 */
public final class HrmsPostgres {

  public static final String IMAGE = "postgres:16-alpine";
  private static PostgreSQLContainer<?> container;

  private HrmsPostgres() {}

  public static synchronized PostgreSQLContainer<?> container() {
    if (container == null) {
      container =
          new PostgreSQLContainer<>(IMAGE)
              .withDatabaseName("hrms")
              .withUsername("hrms")
              .withPassword("hrms")
              .withReuse(false);
      container.start();
      Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
    }
    return container;
  }

  public static DataSource dataSource() {
    PostgreSQLContainer<?> c = container();
    return new DriverManagerDataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword());
  }

  public static Flyway flyway() {
    return Flyway.configure()
        .dataSource(dataSource())
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .load();
  }

  /** Drops everything and re-applies V1..Vn from scratch. */
  public static void resetSchema() {
    Flyway flyway = flyway();
    flyway.clean();
    flyway.migrate();
  }

  /** Loads tools/fixtures/pg/*.sql (the frozen seed) in file order. */
  public static void loadFixtures(JdbcTemplate jdbc) {
    for (Path sql : fixtureFiles()) {
      try {
        for (String statement : splitStatements(Files.readString(sql))) {
          jdbc.execute(statement);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  public static List<Path> fixtureFiles() {
    Path dir = repoRoot().resolve("tools/fixtures/pg");
    try (var stream = Files.list(dir)) {
      return stream.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("tools/fixtures"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException("repo root (tools/fixtures) not found");
    }
    return p;
  }

  static List<String> splitStatements(String script) {
    StringBuilder current = new StringBuilder();
    List<String> out = new java.util.ArrayList<>();
    boolean inQuote = false;
    for (String line : script.split("\n")) {
      if (!inQuote && line.trim().startsWith("--")) {
        continue;
      }
      for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i);
        if (ch == '\'') {
          inQuote = !inQuote;
        }
        if (ch == ';' && !inQuote) {
          String stmt = current.toString().trim();
          if (!stmt.isEmpty()) {
            out.add(stmt);
          }
          current.setLength(0);
        } else {
          current.append(ch);
        }
      }
      current.append('\n');
    }
    String tail = current.toString().trim();
    if (!tail.isEmpty()) {
      out.add(tail);
    }
    return out;
  }

  public static void applyTo(org.springframework.test.context.DynamicPropertyRegistry registry) {
    PostgreSQLContainer<?> c = container();
    registry.add("spring.datasource.url", c::getJdbcUrl);
    registry.add("spring.datasource.username", c::getUsername);
    registry.add("spring.datasource.password", c::getPassword);
  }
}
