package com.acme.hrms.salary;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.format.OracleNumber;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.validation.dto.employee.SalaryChangeRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Salary row scope, proxy write guard, and deferred request validation. */
@Component
public class SalaryAccess {

  private final Validator validator;
  private final String moduleFlag;

  public SalaryAccess(
      Validator validator, @Value("${hrms.proxy.modules.employee:LEGACY}") String moduleFlag) {
    this.validator = validator;
    this.moduleFlag = moduleFlag;
  }

  public void requireReadable(long empId, CallerIdentity caller) {
    if (caller.empId() != empId
        && !caller.authorities().contains("PAYROLL:VIEW")
        && !caller.authorities().contains("EMPLOYEE:EDIT")) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  public void requireWritable() {
    if (!"NEW".equals(moduleFlag)) {
      throw new HrmsException(ErrorCode.MODULE_READ_ONLY);
    }
  }

  public SalaryChangeRequest validate(SalaryChangeRequest body) {
    BigDecimal salary = body == null ? null : body.getBaseSalary();
    if (salary != null && salary.signum() <= 0) {
      throw new HrmsException(
          ErrorCode.SALARY_NOT_POSITIVE,
          "Salary must be positive: " + format(salary),
          "baseSalary");
    }
    body.requireNoUnknownProperties();
    body.requireNoMalformedProperties();
    Set<ConstraintViolation<SalaryChangeRequest>> violations = validator.validate(body);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    return body;
  }

  private static String format(BigDecimal value) {
    return OracleNumber.render(value);
  }
}
