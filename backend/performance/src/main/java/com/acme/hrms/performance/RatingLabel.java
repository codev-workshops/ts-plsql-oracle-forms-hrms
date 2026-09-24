package com.acme.hrms.performance;

import java.math.BigDecimal;

/** {@code PKG_PERFORMANCE.submit_manager_review} label table (COMPONENT_MAPPING.md §6). */
public final class RatingLabel {

  public static final String EXCEPTIONAL = "Exceptional";
  public static final String EXCEEDS = "Exceeds Expectations";
  public static final String MEETS = "Meets Expectations";
  public static final String NEEDS_IMPROVEMENT = "Needs Improvement";
  public static final String UNSATISFACTORY = "Unsatisfactory";

  private RatingLabel() {}

  public static String of(BigDecimal rating) {
    if (rating.compareTo(new BigDecimal("4.5")) >= 0) {
      return EXCEPTIONAL;
    }
    if (rating.compareTo(new BigDecimal("3.5")) >= 0) {
      return EXCEEDS;
    }
    if (rating.compareTo(new BigDecimal("2.5")) >= 0) {
      return MEETS;
    }
    if (rating.compareTo(new BigDecimal("1.5")) >= 0) {
      return NEEDS_IMPROVEMENT;
    }
    return UNSATISFACTORY;
  }
}
