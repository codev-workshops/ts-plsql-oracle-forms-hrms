package com.acme.hrms.tools.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffAndCanonicalTest {

  @Test
  void numbersAreCanonicalisedAcrossDrivers() {
    assertThat(Canonical.number(new BigDecimal("1500.00"))).isEqualTo("1500");
    assertThat(Canonical.number(new BigDecimal("1.5E+3"))).isEqualTo("1500");
    assertThat(Canonical.number(new BigDecimal("0.10"))).isEqualTo("0.1");
    assertThat(Canonical.number(new BigDecimal("-3.250"))).isEqualTo("-3.25");
  }

  @Test
  void midnightTimestampsCollapseToDates() {
    assertThat(Canonical.dateTime(LocalDateTime.of(2024, 6, 30, 0, 0))).isEqualTo("2024-06-30");
    assertThat(Canonical.dateTime(LocalDateTime.of(2024, 6, 30, 8, 15, 3, 999)))
        .isEqualTo("2024-06-30T08:15:03");
  }

  @Test
  void diffReportsChangedMissingAndExtraCells() {
    List<Cell> expected =
        List.of(
            new Cell("VW_LEAVE_SUMMARY", 1, "USED", "3"),
            new Cell("VW_LEAVE_SUMMARY", 1, "PENDING", "1"),
            new Cell("VW_LEAVE_SUMMARY", 2, "USED", "0"));
    List<Cell> actual =
        List.of(
            new Cell("VW_LEAVE_SUMMARY", 1, "USED", "3"),
            new Cell("VW_LEAVE_SUMMARY", 1, "PENDING", "2"),
            new Cell("VW_LEAVE_SUMMARY", 3, "USED", "0"));
    Diff d = Diff.of(expected, actual);
    assertThat(d.reconciled()).isFalse();
    assertThat(d.mismatches())
        .containsExactly(
            new Diff.Mismatch("VW_LEAVE_SUMMARY", 1, "PENDING", "1", "2"),
            new Diff.Mismatch("VW_LEAVE_SUMMARY", 2, "USED", "0", null),
            new Diff.Mismatch("VW_LEAVE_SUMMARY", 3, "USED", null, "0"));
    assertThat(d.toMarkdown())
        .contains("**DIFF**")
        .contains("| VW_LEAVE_SUMMARY | 1 | PENDING | 1 | 2 |");
  }

  @Test
  void csvEscapingRoundTrips() {
    Cell c = new Cell("VW_PENDING_APPROVALS", 1, "DETAILS", "2 day(s) 07/01-07/02, \"x\"");
    assertThat(Baseline.parse(c.toCsv())).isEqualTo(c);
  }

  @Test
  void namedBindsBecomeJdbcPlaceholders() {
    NamedSql s =
        new NamedSql("-- comment\nselect :as_of, 'a:b', now()::date from t where d <= :as_of;");
    assertThat(s.jdbcSql()).isEqualTo("select ?, 'a:b', now()::date from t where d <= ?");
    assertThat(s.names()).containsExactly("as_of", "as_of");
  }
}
