package com.hrms.platform.auth;

/** Authenticated caller, resolved from the bearer session token on every /api request. */
public record CurrentUser(long empId, String username, String fullName, int gradeLevel, Long managerEmpId,
                          long sessionId) {
}
