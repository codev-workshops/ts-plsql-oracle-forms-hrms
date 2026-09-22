package com.acme.hrms.employee;

/**
 * BUG-07 seam: auth-service closes every session of a terminated employee and disables the login.
 * Owned by auth (user_sessions / user_accounts); employee-service only calls it.
 */
@FunctionalInterface
public interface EmployeeSessionRevoker {

  void revokeSessions(long empId, String actor);
}
