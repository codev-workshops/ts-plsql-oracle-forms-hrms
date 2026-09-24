package com.acme.hrms.common.error;

/** {@code -20001}: employee behind the JWT does not exist or is no longer ACTIVE. */
public class EmployeeNotFoundException extends HrmsException {
  public EmployeeNotFoundException() {
    super(ErrorCode.EMPLOYEE_NOT_FOUND);
  }
}
