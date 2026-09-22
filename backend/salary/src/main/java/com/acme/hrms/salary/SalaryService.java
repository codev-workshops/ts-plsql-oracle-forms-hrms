package com.acme.hrms.salary;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.format.OracleNumber;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.salary.SalaryDtos.SalaryRecord;
import com.acme.hrms.salary.SalaryEmployeeLookup.EmployeeRef;
import com.acme.hrms.salary.SalaryRecordRepository.NewSalary;
import com.acme.hrms.validation.dto.employee.SalaryChangeRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional owner of SALARY_RECORDS and its explicit audit/event side effects. */
@Service
public class SalaryService {

  private static final String TABLE = "SALARY_RECORDS";
  private final SalaryRecordRepository records;
  private final SalaryEmployeeLookup employees;
  private final SalaryAccess access;
  private final AuditService audit;
  private final List<SalaryChangeListener> listeners;

  public SalaryService(
      SalaryRecordRepository records,
      SalaryEmployeeLookup employees,
      SalaryAccess access,
      AuditService audit,
      List<SalaryChangeListener> listeners) {
    this.records = records;
    this.employees = employees;
    this.access = access;
    this.audit = audit;
    this.listeners = listeners;
  }

  @Transactional(readOnly = true)
  public SalaryRecord current(long empId, CallerIdentity caller) {
    requireEmployee(empId);
    access.requireReadable(empId, caller);
    return findCurrent(empId);
  }

  @Transactional(readOnly = true)
  public List<SalaryRecord> history(long empId, CallerIdentity caller) {
    requireEmployee(empId);
    access.requireReadable(empId, caller);
    return records.history(empId);
  }

  @Transactional
  public SalaryRecord change(long empId, SalaryChangeRequest request, String actor) {
    access.requireWritable();
    SalaryChangeRequest req = access.validate(request);
    EmployeeRef employee = requireActive(empId);
    SalaryRecord previous = records.findActive(empId).orElse(null);
    validateEffectiveDate(req.getEffectiveDate(), employee, previous);

    BigDecimal newSalary = req.getBaseSalary().setScale(2, RoundingMode.HALF_UP);
    BigDecimal oldSalary = previous == null ? null : money(previous.baseSalary());
    boolean outOfBand = assertWithinGrade(newSalary, employee.gradeMin(), employee.gradeMax());
    if (previous != null) {
      closeWithAudit(previous, req.getEffectiveDate(), actor);
    }
    long id =
        records.insert(
            new NewSalary(
                empId,
                req.getEffectiveDate(),
                null,
                newSalary,
                defaultValue(req.getCurrencyCode(), "USD"),
                defaultValue(req.getPayFrequency(), "MONTHLY"),
                defaultValue(req.getSalaryBasis(), "ANNUAL"),
                req.getChangeReason(),
                changePct(oldSalary, newSalary),
                outOfBand,
                actor));
    SalaryRecord created = records.findById(id).orElseThrow();
    publish(empId, req.getEffectiveDate(), oldSalary, newSalary, req.getChangeReason(), actor);
    auditInsert(id, empId, newSalary, req.getEffectiveDate(), actor);
    return created;
  }

  @Transactional
  public SalaryRecord createInitial(
      long empId, BigDecimal baseSalary, LocalDate effectiveDate, String actor) {
    if (baseSalary == null) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "Salary is required", "baseSalary");
    }
    if (baseSalary.signum() <= 0) {
      throw new HrmsException(
          ErrorCode.SALARY_NOT_POSITIVE,
          "Salary must be positive: " + OracleNumber.render(baseSalary),
          "baseSalary");
    }
    EmployeeRef employee = requireActive(empId);
    BigDecimal salary = baseSalary.setScale(2, RoundingMode.HALF_UP);
    long id =
        records.insert(
            new NewSalary(
                empId,
                effectiveDate,
                null,
                salary,
                "USD",
                "MONTHLY",
                "ANNUAL",
                "INITIAL",
                null,
                assertWithinGrade(salary, employee.gradeMin(), employee.gradeMax()),
                actor));
    SalaryRecord created = records.findById(id).orElseThrow();
    publish(empId, effectiveDate, null, salary, "INITIAL", actor);
    auditInsert(id, empId, salary, effectiveDate, actor);
    return created;
  }

  @Transactional
  public void closeActive(long empId, LocalDate endDate, String actor) {
    SalaryRecord previous = records.findActive(empId).orElse(null);
    if (previous == null) {
      return;
    }
    closeWithAudit(previous, endDate, actor);
  }

  private void closeWithAudit(SalaryRecord previous, LocalDate endDate, String actor) {
    records.closeActive(previous.empId(), endDate, actor);
    BigDecimal salary = money(previous.baseSalary());
    audit.log(
        TABLE,
        previous.salaryId(),
        Action.UPDATE,
        "{\"salary\":" + OracleNumber.render(salary) + ",\"active\":\"Y\"}",
        "{\"salary\":" + OracleNumber.render(salary) + ",\"active\":\"N\"}",
        actor,
        null,
        null);
  }

  public boolean assertWithinGrade(
      BigDecimal salary, @Nullable BigDecimal min, @Nullable BigDecimal max) {
    return salary != null
        && min != null
        && max != null
        && (salary.compareTo(min) < 0 || salary.compareTo(max) > 0);
  }

  public static BigDecimal compaRatio(
      BigDecimal base, @Nullable BigDecimal min, @Nullable BigDecimal max) {
    if (base == null || min == null || max == null) {
      return null;
    }
    BigDecimal midpoint = min.add(max).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    if (midpoint.signum() == 0) {
      return null;
    }
    return base.divide(midpoint, 10, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(1, RoundingMode.HALF_UP);
  }

  public static BigDecimal changePct(@Nullable BigDecimal oldSalary, BigDecimal newSalary) {
    if (oldSalary == null || oldSalary.signum() == 0 || newSalary == null) {
      return null;
    }
    return newSalary
        .subtract(oldSalary)
        .divide(oldSalary, 10, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private EmployeeRef requireEmployee(long empId) {
    return employees.find(empId).orElseThrow(() -> new HrmsException(ErrorCode.EMPLOYEE_NOT_FOUND));
  }

  private SalaryRecord findCurrent(long empId) {
    return records
        .findActive(empId)
        .orElseThrow(
            () ->
                new HrmsException(
                    ErrorCode.NO_ACTIVE_SALARY,
                    "No active salary record for employee " + empId,
                    null));
  }

  private EmployeeRef requireActive(long empId) {
    EmployeeRef employee = requireEmployee(empId);
    if (!"ACTIVE".equals(employee.employmentStatus())) {
      throw new HrmsException(ErrorCode.EMPLOYEE_NOT_FOUND);
    }
    return employee;
  }

  private static void validateEffectiveDate(
      LocalDate effectiveDate, EmployeeRef employee, @Nullable SalaryRecord previous) {
    if (effectiveDate.isBefore(employee.hireDate())
        || (previous != null && effectiveDate.isBefore(previous.effectiveDate()))) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED,
          "Effective date must be on or after hire date and current salary effective date",
          "effectiveDate");
    }
  }

  private void publish(
      long empId,
      LocalDate date,
      BigDecimal oldSalary,
      BigDecimal newSalary,
      String reason,
      String actor) {
    for (SalaryChangeListener listener : listeners) {
      listener.onSalaryChanged(
          new SalaryChangeEvent(empId, date, oldSalary, newSalary, reason, actor));
    }
  }

  private void auditInsert(
      long id, long empId, BigDecimal salary, LocalDate effectiveDate, String actor) {
    audit.log(
        TABLE,
        id,
        Action.INSERT,
        null,
        "{\"emp_id\":"
            + empId
            + ",\"salary\":"
            + OracleNumber.render(salary)
            + ",\"effective\":\""
            + effectiveDate
            + "\"}",
        actor,
        null,
        null);
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  private static String defaultValue(@Nullable String value, String fallback) {
    return value == null ? fallback : value;
  }
}
