package com.acme.hrms.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pure rules of COMPONENT_MAPPING.md §6: rating label thresholds and goal status derivation. */
class RatingLabelAndGoalStatusTest {

  @ParameterizedTest
  @CsvSource({
    "5.0, Exceptional",
    "4.5, Exceptional",
    "4.4, Exceeds Expectations",
    "3.5, Exceeds Expectations",
    "3.4, Meets Expectations",
    "2.5, Meets Expectations",
    "2.4, Needs Improvement",
    "1.5, Needs Improvement",
    "1.4, Unsatisfactory",
    "1.0, Unsatisfactory"
  })
  void ratingLabelThresholds(String rating, String label) {
    assertThat(RatingLabel.of(new BigDecimal(rating))).isEqualTo(label);
  }

  @Test
  void explicitGoalStatusWinsWithoutTransitionValidation() {
    assertThat(GoalService.deriveStatus(new BigDecimal("100"), "DEFERRED", "IN_PROGRESS"))
        .isEqualTo("DEFERRED");
    assertThat(GoalService.deriveStatus(BigDecimal.ZERO, "CANCELLED", "COMPLETED"))
        .isEqualTo("CANCELLED");
  }

  @Test
  void goalStatusDerivedFromProgress() {
    assertThat(GoalService.deriveStatus(new BigDecimal("100"), null, "NOT_STARTED"))
        .isEqualTo("COMPLETED");
    assertThat(GoalService.deriveStatus(new BigDecimal("100.00"), null, "DEFERRED"))
        .isEqualTo("COMPLETED");
    assertThat(GoalService.deriveStatus(new BigDecimal("0.01"), null, "NOT_STARTED"))
        .isEqualTo("IN_PROGRESS");
    assertThat(GoalService.deriveStatus(new BigDecimal("99.99"), null, "DEFERRED"))
        .isEqualTo("IN_PROGRESS");
    assertThat(GoalService.deriveStatus(BigDecimal.ZERO, null, "DEFERRED")).isEqualTo("DEFERRED");
    assertThat(GoalService.deriveStatus(new BigDecimal("0.00"), null, "NOT_STARTED"))
        .isEqualTo("NOT_STARTED");
  }
}
