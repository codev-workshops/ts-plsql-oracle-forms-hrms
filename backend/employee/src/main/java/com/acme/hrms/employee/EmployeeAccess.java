package com.acme.hrms.employee;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.format.OracleNumber;
import com.acme.hrms.common.param.SystemParameterService;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.validation.dto.employee.EmployeeCreateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeUpdateRequest;
import com.acme.hrms.validation.dto.employee.StrictRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Row scope, proxy write guard and the legacy-coded body checks that must win over generic Bean
 * Validation ({@code -20010}, {@code -20501}, {@code -20101}; error-codes.md §3 step 5). Unknown
 * body properties ({@code additionalProperties: false}) are rejected in the same step, after the
 * legacy-coded checks and before Bean Validation.
 */
@Component
public class EmployeeAccess {

  public static final String EDIT = "EMPLOYEE:EDIT";

  private final Validator validator;
  private final SystemParameterService parameters;
  private final Clock clock;
  private final String moduleFlag;

  public EmployeeAccess(
      Validator validator,
      SystemParameterService parameters,
      Clock clock,
      @Value("${hrms.proxy.modules.employee:LEGACY}") String moduleFlag) {
    this.validator = validator;
    this.parameters = parameters;
    this.clock = clock;
    this.moduleFlag = moduleFlag;
  }

  public void requireWritable() {
    if (!"NEW".equals(moduleFlag)) {
      throw new HrmsException(ErrorCode.MODULE_READ_ONLY);
    }
  }

  public static boolean canEdit(CallerIdentity caller) {
    return caller.authorities().contains(EDIT);
  }

  /** {@code EMPLOYEE:EDIT} holders reach any employee; everyone else only themselves. */
  public static void requireSelfOrEdit(long empId, CallerIdentity caller) {
    if (caller.empId() != empId && !canEdit(caller)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  /** {@code ssnLast4} scope: {@code EMPLOYEE:EDIT} or self. */
  public static boolean canSeeSsn(long empId, CallerIdentity caller) {
    return caller.empId() == empId || canEdit(caller);
  }

  /** Salary read scope for history rows: {@code PAYROLL:VIEW}, {@code EMPLOYEE:EDIT} or self. */
  public static boolean canSeeSalary(long empId, CallerIdentity caller) {
    return caller.empId() == empId
        || canEdit(caller)
        || caller.authorities().contains("PAYROLL:VIEW");
  }

  public <T> T validate(@Nullable T body) {
    if (body == null) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "Malformed request body", "body");
    }
    if (body instanceof StrictRequest strict) {
      strict.requireNoUnknownProperties();
    }
    Set<ConstraintViolation<T>> violations = validator.validate(body);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    return body;
  }

  public EmployeeCreateRequest validateCreate(@Nullable EmployeeCreateRequest body) {
    if (body == null) {
      return validate(body);
    }
    requireNames(body.getFirstName(), body.getLastName());
    requireHireDateWithinLimit(body.getHireDate());
    requirePositive(body.getInitialSalary());
    return validate(body);
  }

  public EmployeeUpdateRequest validateUpdate(@Nullable EmployeeUpdateRequest body) {
    if (body == null) {
      return validate(body);
    }
    requireNames(body.getFirstName(), body.getLastName());
    return validate(body);
  }

  /** {@code create_employee}: {@code -20010} when either name is missing. */
  static void requireNames(@Nullable String firstName, @Nullable String lastName) {
    if (firstName == null || firstName.isBlank()) {
      throw new HrmsException(ErrorCode.NAMES_REQUIRED, "firstName");
    }
    if (lastName == null || lastName.isBlank()) {
      throw new HrmsException(ErrorCode.NAMES_REQUIRED, "lastName");
    }
  }

  /**
   * {@code TRG_EMP_BEFORE_INSERT}: {@code -20501} against the single VAL-01 limit {@code
   * SYSTEM_PARAMETERS HR.MAX_FUTURE_HIRE_DAYS} (default 90).
   */
  void requireHireDateWithinLimit(@Nullable LocalDate hireDate) {
    requireHireDateWithinLimit(hireDate, parameters.maxFutureHireDays(), clock);
  }

  static void requireHireDateWithinLimit(
      @Nullable LocalDate hireDate, int maxFutureDays, Clock clock) {
    if (hireDate != null && hireDate.isAfter(LocalDate.now(clock).plusDays(maxFutureDays))) {
      throw new HrmsException(
          ErrorCode.HIRE_DATE_TOO_FAR,
          "Hire date cannot be more than " + maxFutureDays + " days in the future",
          "hireDate");
    }
  }

  static void requirePositive(@Nullable BigDecimal initialSalary) {
    if (initialSalary != null && initialSalary.signum() <= 0) {
      throw new HrmsException(
          ErrorCode.SALARY_NOT_POSITIVE,
          "Salary must be positive: " + OracleNumber.render(initialSalary),
          "initialSalary");
    }
  }
}
