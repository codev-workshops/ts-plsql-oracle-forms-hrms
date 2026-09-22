package com.acme.hrms.leave;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.leave.LeaveDtos.LeaveRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Row-level scoping rules of contracts/p2-leave/openapi.yaml, the deferred Bean Validation that
 * keeps the frozen evaluation order (error-codes.md: authority → validation → caller → existence →
 * scope → domain), and the read-only {@code employees} lookups leave-service needs (caller location
 * / hire date / manager). Controllers never annotate bodies with {@code @Valid}.
 */
@Component
public class LeaveAccess {

  public static final String APPROVE = "LEAVE:APPROVE";
  public static final String VIEW_ALL = "LEAVE:VIEW_ALL";

  /** Cross-field {@code @AssertTrue} rules and the {@code field} error-codes.md assigns them. */
  private static final Map<String, String> CROSS_FIELD =
      Map.of("halfDaySingleDate", "endDate", "halfDayPeriodConsistent", "halfDayPeriod");

  /** {@code employees} projection of an ACTIVE caller (PKG_LEAVE reads the same columns). */
  public record Employee(
      long empId,
      String firstName,
      String lastName,
      String email,
      LocalDate hireDate,
      @Nullable String locationCode,
      @Nullable Long managerEmpId) {}

  private final Validator validator;
  private final JdbcTemplate jdbc;

  public LeaveAccess(Validator validator, JdbcTemplate jdbc) {
    this.validator = validator;
    this.jdbc = jdbc;
  }

  /** Rule 1 of submit / every "mine" read: caller must be ACTIVE, else {@code 404 -20001}. */
  public Employee requireActive(long empId) {
    return jdbc
        .query(
            "select emp_id, first_name, last_name, email, hire_date, location_code, manager_emp_id"
                + " from employees where emp_id = ? and employment_status = 'ACTIVE'"
                + " and active_flag = 'Y'",
            (rs, i) ->
                new Employee(
                    rs.getLong("emp_id"),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    rs.getString("email"),
                    rs.getObject("hire_date", LocalDate.class),
                    rs.getString("location_code"),
                    nullableLong(rs.getObject("manager_emp_id"))),
            empId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new HrmsException(ErrorCode.EMPLOYEE_NOT_FOUND));
  }

  /** listLeaveRequestsForEmployee: any employee row, terminated included. */
  public void requireExists(long empId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from employees where emp_id = ?", Integer.class, empId);
    if (n == null || n == 0) {
      throw new HrmsException(ErrorCode.EMPLOYEE_NOT_FOUND);
    }
  }

  public Optional<Employee> findActive(long empId) {
    try {
      return Optional.of(requireActive(empId));
    } catch (HrmsException e) {
      return Optional.empty();
    }
  }

  @Nullable
  private static Long nullableLong(@Nullable Object v) {
    return v instanceof Number n ? n.longValue() : null;
  }

  static boolean isOwner(LeaveRequest r, CallerIdentity caller) {
    return r.empId() == caller.empId();
  }

  static boolean isDesignatedApprover(LeaveRequest r, CallerIdentity caller) {
    return r.approverEmpId() != null && r.approverEmpId() == caller.empId();
  }

  /** getLeaveRequest: owner, designated approver or LEAVE:VIEW_ALL. */
  public void requireReadable(LeaveRequest r, CallerIdentity caller) {
    if (!isOwner(r, caller)
        && !isDesignatedApprover(r, caller)
        && !caller.authorities().contains(VIEW_ALL)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  /** cancelLeaveRequest: owner only, even for approvers. */
  public void requireOwner(LeaveRequest r, CallerIdentity caller) {
    if (!isOwner(r, caller)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  /** approve / reject: designated approver or LEAVE:APPROVE, never the owner. */
  public void requireApprover(LeaveRequest r, CallerIdentity caller) {
    if (isOwner(r, caller)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
    if (!isDesignatedApprover(r, caller) && !caller.authorities().contains(APPROVE)) {
      throw new HrmsException(ErrorCode.FORBIDDEN);
    }
  }

  /** Bean Validation of a body / query object; rendered as {@code VALIDATION_FAILED}. */
  public <T> T validate(T body) {
    Set<ConstraintViolation<T>> violations = validator.validate(body);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    return body;
  }

  /**
   * Same, but a failed {@code dateOrderValid} constraint is the legacy {@code -20210} (contract:
   * "endDate < startDate → -20210" on submit, "start > end → 400 -20210" on business-days).
   */
  public <T> T validateWithDateOrder(T body, String endField) {
    Set<ConstraintViolation<T>> violations = validator.validate(body);
    if (violations.isEmpty()) {
      return body;
    }
    boolean dateOrder =
        violations.stream().anyMatch(v -> "dateOrderValid".equals(v.getPropertyPath().toString()));
    if (dateOrder && violations.size() == 1) {
      throw new HrmsException(ErrorCode.LEAVE_DATE_ORDER, endField);
    }
    Set<ConstraintViolation<T>> rest =
        violations.stream()
            .filter(v -> !"dateOrderValid".equals(v.getPropertyPath().toString()))
            .collect(Collectors.toSet());
    boolean onlyCrossField =
        rest.stream().allMatch(v -> CROSS_FIELD.containsKey(v.getPropertyPath().toString()));
    if (onlyCrossField) {
      ConstraintViolation<T> v =
          rest.stream()
              .min(
                  (a, b) ->
                      a.getPropertyPath().toString().compareTo(b.getPropertyPath().toString()))
              .orElseThrow();
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED,
          v.getMessage(),
          CROSS_FIELD.get(v.getPropertyPath().toString()));
    }
    throw new ConstraintViolationException(rest);
  }
}
