package com.acme.hrms.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The ErrorCode enum must match the frozen error-codes.md of every implemented phase exactly. */
class ErrorCodeTest {

  private static final Pattern WIRE = Pattern.compile("^(-20[0-9]{3}|[A-Z][A-Z0-9_]{2,63})$");

  @Test
  void everyValueMatchesTheApiErrorPattern() {
    for (ErrorCode c : ErrorCode.values()) {
      assertThat(c.value()).matches(WIRE);
    }
  }

  @Test
  void legacyNumbersAreIntegersForErrorLog() {
    assertThat(ErrorCode.INVALID_CREDENTIALS.legacyNumber()).isEqualTo(-20301);
    assertThat(ErrorCode.VALIDATION_FAILED.legacyNumber()).isNull();
  }

  @Test
  void statusesMatchErrorCodesMd() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("contracts/p0-foundation/error-codes.md"))) {
      root = root.getParent();
    }
    Pattern row =
        Pattern.compile("^\\|\\s*`(-20\\d{3}|[A-Z][A-Z0-9_]+)`\\s*\\|\\s*(\\d{3})\\s*\\|");
    Set<String> matched = new TreeSet<>();
    for (String contract : List.of("p0-foundation", "p1-performance")) {
      List<String> lines =
          Files.readAllLines(root.resolve("contracts/" + contract + "/error-codes.md"));
      for (String line : lines) {
        Matcher m = row.matcher(line);
        if (m.find()) {
          ErrorCode code = ErrorCode.fromValue(m.group(1));
          assertThat(code.status())
              .as("status of %s in %s", m.group(1), contract)
              .isEqualTo(HttpStatus.valueOf(Integer.parseInt(m.group(2))));
          matched.add(m.group(1));
        }
      }
    }
    assertThat(matched).as("codes parsed from error-codes.md").hasSize(ErrorCode.values().length);
  }
}
