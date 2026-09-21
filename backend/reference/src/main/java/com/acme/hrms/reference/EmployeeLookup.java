package com.acme.hrms.reference;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Shared read-only lookup on {@code employees} (contracts/p1-performance/error-codes.md §1, {@code
 * -20001}): resolves the caller's {@code jwt.empId} to an ACTIVE employee. Other modules never
 * query {@code employees} for existence themselves.
 */
@Repository
public class EmployeeLookup {

  private final JdbcTemplate jdbc;

  public EmployeeLookup(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @throws HrmsException {@link ErrorCode#EMPLOYEE_NOT_FOUND} when the employee does not exist, is
   *     not {@code ACTIVE} or has {@code active_flag <> 'Y'}.
   */
  public void requireActive(long empId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from employees where emp_id = ? and employment_status = 'ACTIVE'"
                + " and active_flag = 'Y'",
            Integer.class,
            empId);
    if (n == null || n == 0) {
      throw new HrmsException(ErrorCode.EMPLOYEE_NOT_FOUND);
    }
  }
}
