package com.acme.hrms.validation.dto.leave;

/**
 * Shared bounds for the free-text columns of LEAVE_REQUESTS (REASON / APPROVAL_COMMENTS,
 * VARCHAR2(4000)).
 */
final class LeaveText {
  static final int MAX = 4000;

  private LeaveText() {}

  static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
