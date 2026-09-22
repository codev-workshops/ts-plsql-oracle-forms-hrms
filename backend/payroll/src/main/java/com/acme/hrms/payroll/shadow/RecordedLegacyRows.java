package com.acme.hrms.payroll.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Recorded legacy expectations ({@code tests/golden/payroll/<periodId>.json}) – the only legacy
 * source in GOLDEN-ORACLE MODE = OFF. Each row is what PKG_PAYROLL would have written for the
 * period, derived by hand from the seed data and the package source (see the README in that
 * directory); {@code explanation} pre-declares a divergence from the frozen list.
 */
@Component
public class RecordedLegacyRows {

  public record Row(
      long empId,
      long elementId,
      String elementType,
      String amount,
      String status,
      @Nullable String errorCode,
      @Nullable String explanation) {
    public BigDecimal amountValue() {
      return new BigDecimal(amount);
    }
  }

  public record Pack(long periodId, @Nullable String derivedFrom, List<Row> rows) {}

  private final String directory;
  private final ObjectMapper mapper;

  public RecordedLegacyRows(
      @Value("${hrms.payroll.shadow.recorded-dir:tests/golden/payroll}") String directory,
      ObjectMapper mapper) {
    this.directory = directory;
    this.mapper = mapper;
  }

  public Optional<Pack> load(long periodId) {
    Path file = resolve().resolve(periodId + ".json");
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(Files.readAllBytes(file), Pack.class));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read recorded legacy pack " + file, e);
    }
  }

  /** Absolute paths as-is; relative paths are resolved against cwd and each of its parents. */
  Path resolve() {
    Path configured = Path.of(directory);
    if (configured.isAbsolute()) {
      return configured;
    }
    Path base = Path.of("").toAbsolutePath();
    while (base != null) {
      Path candidate = base.resolve(configured);
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      base = base.getParent();
    }
    return configured.toAbsolutePath();
  }
}
