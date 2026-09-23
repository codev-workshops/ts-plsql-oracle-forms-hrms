package com.acme.hrms.employee;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.param.SystemParameterService;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.employee.DependentRepository.DependentRow;
import com.acme.hrms.employee.DependentRepository.DependentWrite;
import com.acme.hrms.employee.EmergencyContactRepository.ContactWrite;
import com.acme.hrms.employee.EmployeeDtos.Dependent;
import com.acme.hrms.employee.EmployeeDtos.EmergencyContact;
import com.acme.hrms.employee.EmployeeDtos.EmployeeDetail;
import com.acme.hrms.employee.EmployeeDtos.EmployeeHistoryEntry;
import com.acme.hrms.employee.EmployeeHistoryRepository.HistoryRow;
import com.acme.hrms.employee.EmployeeRepository.EmployeeUpdate;
import com.acme.hrms.employee.EmployeeRepository.NewEmployee;
import com.acme.hrms.salary.SalaryService;
import com.acme.hrms.validation.dto.employee.DependentRequest;
import com.acme.hrms.validation.dto.employee.EmergencyContactRequest;
import com.acme.hrms.validation.dto.employee.EmployeeCreateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeTerminateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeTransferRequest;
import com.acme.hrms.validation.dto.employee.EmployeeUpdateRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code PKG_EMPLOYEE} + {@code TRG_EMPLOYEES} as Java service invariants (COMPONENT_MAPPING §3.2).
 * Steps follow the frozen order of contracts/p3-employee/openapi.yaml. Salary rows are reached only
 * through {@link SalaryService} (ARCH-01); sessions only through {@link EmployeeSessionRevoker}.
 */
@Service
public class EmployeeService {

  static final String TABLE = "EMPLOYEES";

  private final EmployeeRepository employees;
  private final EmployeeHistoryRepository history;
  private final DependentRepository dependents;
  private final EmergencyContactRepository contacts;
  private final SalaryService salaries;
  private final AuditService audit;
  private final SensitiveFieldCipher cipher;
  private final EmployeeSessionRevoker sessions;
  private final ApplicationEventPublisher events;
  private final SystemParameterService parameters;
  private final Clock clock;

  public EmployeeService(
      EmployeeRepository employees,
      EmployeeHistoryRepository history,
      DependentRepository dependents,
      EmergencyContactRepository contacts,
      SalaryService salaries,
      AuditService audit,
      SensitiveFieldCipher cipher,
      EmployeeSessionRevoker sessions,
      ApplicationEventPublisher events,
      SystemParameterService parameters,
      Clock clock) {
    this.employees = employees;
    this.history = history;
    this.dependents = dependents;
    this.contacts = contacts;
    this.salaries = salaries;
    this.audit = audit;
    this.cipher = cipher;
    this.sessions = sessions;
    this.events = events;
    this.parameters = parameters;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ reads

  @Transactional(readOnly = true)
  public EmployeeDetail get(long empId, CallerIdentity caller) {
    return detail(require(empId), caller);
  }

  @Transactional(readOnly = true)
  public List<EmployeeHistoryEntry> history(long empId, CallerIdentity caller) {
    require(empId);
    List<EmployeeHistoryEntry> rows = history.findByEmployee(empId);
    if (EmployeeAccess.canSeeSalary(empId, caller)) {
      return rows;
    }
    return rows.stream().map(EmployeeHistoryEntry::withoutSalary).toList();
  }

  // ----------------------------------------------------------------- create

  /** {@code PKG_EMPLOYEE.create_employee}: the only insert path into {@code employees}. */
  @Transactional
  public EmployeeDetail create(EmployeeCreateRequest r, CallerIdentity caller) {
    EmployeeAccess.requireNames(r.getFirstName(), r.getLastName());
    EmployeeAccess.requireHireDateWithinLimit(
        r.getHireDate(), parameters.maxFutureHireDays(), clock);
    EmployeeAccess.requirePositive(r.getInitialSalary());
    long deptId = r.getDeptId();
    long jobId = r.getJobId();
    Long managerId = toLong(r.getManagerEmpId());
    requireDepartment(deptId);
    requireJob(jobId);
    if (managerId != null) {
      requireManager(managerId, null);
    }
    if (r.getLocationCode() != null) {
      requireLocation(r.getLocationCode());
    }
    if (r.getEmail() != null) {
      requireEmailFree(r.getEmail(), null);
    }
    String actor = caller.userId();
    String employmentType = r.getEmploymentType() == null ? "FULL_TIME" : r.getEmploymentType();
    String empNumber = employees.nextEmpNumber();
    long empId;
    try {
      empId =
          employees.insert(
              new NewEmployee(
                  empNumber,
                  legacyName(r.getFirstName()),
                  r.getMiddleName(),
                  legacyName(r.getLastName()),
                  r.getDateOfBirth(),
                  r.getGender(),
                  r.getMaritalStatus(),
                  r.getNationality(),
                  cipher.encrypt(r.getSsn()),
                  r.getEmail(),
                  r.getPhoneWork(),
                  r.getPhoneMobile(),
                  r.getAddressLine1(),
                  r.getAddressLine2(),
                  r.getCity(),
                  r.getStateProvince(),
                  r.getPostalCode(),
                  r.getCountryCode(),
                  r.getHireDate(),
                  deptId,
                  jobId,
                  managerId,
                  r.getLocationCode(),
                  employmentType,
                  r.getNotes(),
                  actor));
    } catch (DuplicateKeyException e) {
      throw duplicate(e, r.getEmail());
    }
    BigDecimal salary = null;
    if (r.getInitialSalary() != null) {
      salaries.createInitial(empId, r.getInitialSalary(), r.getHireDate(), actor);
      salary = r.getInitialSalary().setScale(2, RoundingMode.HALF_UP);
    }
    history.insert(
        new HistoryRow(
            empId,
            "HIRE",
            r.getHireDate(),
            null,
            deptId,
            null,
            jobId,
            null,
            managerId,
            null,
            salary,
            null,
            r.getLocationCode(),
            null,
            null,
            actor));
    audit.log(
        TABLE,
        empId,
        Action.INSERT,
        null,
        "{\"emp_number\":\""
            + empNumber
            + "\",\"dept_id\":"
            + deptId
            + ",\"job_id\":"
            + jobId
            + "}",
        actor,
        null,
        caller.jti());
    return detail(require(empId), caller);
  }

  // ----------------------------------------------------------------- update

  /**
   * {@code update_employee} + {@code TRG_EMP_BEFORE_UPDATE}; full replacement of the editable set.
   */
  @Transactional
  public EmployeeDetail update(
      long empId, int expectedVersion, EmployeeUpdateRequest r, CallerIdentity caller) {
    EmployeeRow before = lockRequired(empId);
    if (before.terminated()) {
      throw new HrmsException(ErrorCode.TERMINATED_REACTIVATION);
    }
    EmployeeAccess.requireNames(r.getFirstName(), r.getLastName());
    long jobId = r.getJobId();
    Long managerId = toLong(r.getManagerEmpId());
    requireJob(jobId);
    if (managerId != null) {
      requireManager(managerId, empId);
    }
    if (r.getEmail() != null) {
      requireEmailFree(r.getEmail(), empId);
    }
    String actor = caller.userId();
    String employmentType =
        r.getEmploymentType() == null ? before.employmentType() : r.getEmploymentType();
    boolean updated;
    try {
      updated =
          employees.update(
              empId,
              expectedVersion,
              new EmployeeUpdate(
                  legacyName(r.getFirstName()),
                  r.getMiddleName(),
                  legacyName(r.getLastName()),
                  r.getDateOfBirth(),
                  r.getGender(),
                  r.getMaritalStatus(),
                  r.getNationality(),
                  cipher.encrypt(r.getSsn()),
                  r.getEmail(),
                  r.getPhoneWork(),
                  r.getPhoneMobile(),
                  r.getAddressLine1(),
                  r.getAddressLine2(),
                  r.getCity(),
                  r.getStateProvince(),
                  r.getPostalCode(),
                  r.getCountryCode(),
                  jobId,
                  managerId,
                  employmentType,
                  r.getNotes(),
                  actor));
    } catch (DuplicateKeyException e) {
      throw duplicate(e, r.getEmail());
    }
    if (!updated) {
      throw new HrmsException(ErrorCode.CONFLICT);
    }
    if (before.jobId() != jobId) {
      history.insert(
          new HistoryRow(
              empId,
              "PROMOTION",
              LocalDate.now(clock),
              null,
              null,
              before.jobId(),
              jobId,
              null,
              null,
              null,
              null,
              null,
              null,
              "JOB_CHANGE",
              null,
              actor));
    }
    EmployeeRow after = require(empId);
    audit.log(
        TABLE,
        empId,
        Action.UPDATE,
        changedColumns(before, after, r.getSsn() != null, true),
        changedColumns(before, after, r.getSsn() != null, false),
        actor,
        null,
        caller.jti());
    return detail(after, caller);
  }

  // -------------------------------------------------------------- terminate

  /** {@code terminate_employee}: the only "delete"; a second call is {@code -20005}. */
  @Transactional
  public EmployeeDetail terminate(long empId, EmployeeTerminateRequest r, CallerIdentity caller) {
    EmployeeRow row = lockRequired(empId);
    if (row.terminated()) {
      throw new HrmsException(
          ErrorCode.EMPLOYEE_ALREADY_TERMINATED,
          "Employee " + empId + " is already terminated",
          null);
    }
    LocalDate date = r.getEffectiveDate();
    if (date.isBefore(row.hireDate())) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED,
          "Termination date cannot be before the hire date",
          "effectiveDate");
    }
    String actor = caller.userId();
    employees.terminate(empId, date, r.getReason(), actor);
    salaries.closeActive(empId, date, actor);
    history.insert(
        new HistoryRow(
            empId,
            "TERMINATION",
            date,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            r.getReason(),
            r.getComments(),
            actor));
    audit.log(
        TABLE,
        empId,
        Action.UPDATE,
        "{\"employment_status\":\"" + row.employmentStatus() + "\",\"active_flag\":\"Y\"}",
        "{\"employment_status\":\"TERMINATED\",\"active_flag\":\"N\",\"termination_date\":\""
            + date
            + "\"}",
        actor,
        null,
        caller.jti());
    sessions.revokeSessions(empId, actor);
    events.publishEvent(new EmployeeTerminatedEvent(empId, date, r.getReason(), actor));
    return detail(require(empId), caller);
  }

  // --------------------------------------------------------------- transfer

  /** {@code transfer_employee}: the only operation that changes {@code deptId}. */
  @Transactional
  public EmployeeDetail transfer(long empId, EmployeeTransferRequest r, CallerIdentity caller) {
    EmployeeRow before = lockRequired(empId);
    if (!"ACTIVE".equals(before.employmentStatus())) {
      throw new HrmsException(
          ErrorCode.EMPLOYEE_NOT_ACTIVE,
          "Cannot transfer non-active employee. Status: " + before.employmentStatus(),
          null);
    }
    long deptId = r.getDeptId();
    requireDepartment(deptId);
    long jobId = r.getNewJobId() == null ? before.jobId() : r.getNewJobId();
    if (r.getNewJobId() != null) {
      requireJob(jobId);
    }
    Long managerId =
        r.getNewManagerEmpId() == null ? before.managerEmpId() : toLong(r.getNewManagerEmpId());
    if (r.getNewManagerEmpId() != null) {
      requireManager(managerId, empId);
    }
    String location =
        r.getNewLocationCode() == null ? before.locationCode() : r.getNewLocationCode();
    if (r.getNewLocationCode() != null) {
      requireLocation(location);
    }
    String actor = caller.userId();
    employees.transfer(empId, deptId, jobId, managerId, location, actor);
    history.insert(
        new HistoryRow(
            empId,
            "TRANSFER",
            r.getEffectiveDate(),
            before.deptId(),
            deptId,
            before.jobId(),
            jobId,
            before.managerEmpId(),
            managerId,
            null,
            null,
            before.locationCode(),
            location,
            r.getReasonCode(),
            r.getComments(),
            actor));
    EmployeeRow after = require(empId);
    audit.log(
        TABLE,
        empId,
        Action.UPDATE,
        changedColumns(before, after, false, true),
        changedColumns(before, after, false, false),
        actor,
        null,
        caller.jti());
    return detail(after, caller);
  }

  // ------------------------------------------------------------- dependents

  @Transactional(readOnly = true)
  public List<Dependent> dependents(long empId, CallerIdentity caller) {
    require(empId);
    EmployeeAccess.requireSelfOrEdit(empId, caller);
    return dependents.findActive(empId).stream().map(this::toDependent).toList();
  }

  @Transactional
  public Dependent addDependent(long empId, DependentRequest r, CallerIdentity caller) {
    EmployeeRow subject = require(empId);
    EmployeeAccess.requireSelfOrEdit(empId, caller);
    requireNotTerminated(subject);
    DependentRow row = dependents.insert(empId, dependentWrite(r, true, caller.userId()));
    audit.log(
        "EMPLOYEE_DEPENDENTS",
        row.dependentId(),
        Action.INSERT,
        null,
        "{\"emp_id\":" + empId + "}",
        caller.userId(),
        null,
        caller.jti());
    return toDependent(row);
  }

  @Transactional
  public Dependent updateDependent(
      long empId, long dependentId, DependentRequest r, CallerIdentity caller) {
    EmployeeRow subject = require(empId);
    DependentRow before =
        dependents
            .findActive(empId, dependentId)
            .orElseThrow(() -> new HrmsException(ErrorCode.DEPENDENT_NOT_FOUND));
    EmployeeAccess.requireSelfOrEdit(empId, caller);
    requireNotTerminated(subject);
    boolean active = r.getActive() == null || r.getActive();
    DependentRow after = dependents.update(dependentId, dependentWrite(r, active, caller.userId()));
    audit.log(
        "EMPLOYEE_DEPENDENTS",
        dependentId,
        Action.UPDATE,
        "{\"active_flag\":\"" + flag(before.active()) + "\"}",
        "{\"active_flag\":\"" + flag(after.active()) + "\"}",
        caller.userId(),
        null,
        caller.jti());
    return toDependent(after);
  }

  private DependentWrite dependentWrite(DependentRequest r, boolean active, String actor) {
    return new DependentWrite(
        r.getFirstName(),
        r.getLastName(),
        r.getRelationship(),
        r.getDateOfBirth(),
        cipher.encrypt(r.getSsn()),
        Boolean.TRUE.equals(r.getBenefitsEnrolled()),
        active,
        actor);
  }

  private Dependent toDependent(DependentRow d) {
    return new Dependent(
        d.dependentId(),
        d.empId(),
        d.firstName(),
        d.lastName(),
        d.relationship(),
        d.dateOfBirth(),
        last4(d.ssnEncrypted()),
        d.benefitsEnrolled(),
        d.active());
  }

  // --------------------------------------------------------------- contacts

  @Transactional(readOnly = true)
  public List<EmergencyContact> contacts(long empId, CallerIdentity caller) {
    require(empId);
    EmployeeAccess.requireSelfOrEdit(empId, caller);
    return contacts.findActive(empId);
  }

  @Transactional
  public EmergencyContact addContact(long empId, EmergencyContactRequest r, CallerIdentity caller) {
    EmployeeRow subject = require(empId);
    EmployeeAccess.requireSelfOrEdit(empId, caller);
    requireNotTerminated(subject);
    EmergencyContact row = contacts.insert(empId, contactWrite(r, true, caller.userId()));
    audit.log(
        "EMERGENCY_CONTACTS",
        row.contactId(),
        Action.INSERT,
        null,
        "{\"emp_id\":" + empId + "}",
        caller.userId(),
        null,
        caller.jti());
    return row;
  }

  @Transactional
  public EmergencyContact updateContact(
      long empId, long contactId, EmergencyContactRequest r, CallerIdentity caller) {
    EmployeeRow subject = require(empId);
    EmergencyContact before =
        contacts
            .findActive(empId, contactId)
            .orElseThrow(() -> new HrmsException(ErrorCode.CONTACT_NOT_FOUND));
    EmployeeAccess.requireSelfOrEdit(empId, caller);
    requireNotTerminated(subject);
    boolean active = r.getActive() == null || r.getActive();
    EmergencyContact after = contacts.update(contactId, contactWrite(r, active, caller.userId()));
    audit.log(
        "EMERGENCY_CONTACTS",
        contactId,
        Action.UPDATE,
        "{\"active_flag\":\"" + flag(before.active()) + "\"}",
        "{\"active_flag\":\"" + flag(after.active()) + "\"}",
        caller.userId(),
        null,
        caller.jti());
    return after;
  }

  private static ContactWrite contactWrite(
      EmergencyContactRequest r, boolean active, String actor) {
    return new ContactWrite(
        r.getContactName(),
        r.getRelationship(),
        r.getPhonePrimary(),
        r.getPhoneSecondary(),
        r.getEmail(),
        r.getPriorityOrder() == null ? 1 : r.getPriorityOrder(),
        active,
        actor);
  }

  // ------------------------------------------------------------- invariants

  private EmployeeRow require(long empId) {
    return employees
        .findById(empId)
        .orElseThrow(() -> new HrmsException(ErrorCode.EMPLOYEE_NOT_FOUND));
  }

  private EmployeeRow lockRequired(long empId) {
    return employees.lock(empId).orElseThrow(() -> new HrmsException(ErrorCode.EMPLOYEE_NOT_FOUND));
  }

  /** Sub-resource writes on a terminated employee are refused like any other write (-20503). */
  private static void requireNotTerminated(EmployeeRow subject) {
    if (subject.terminated()) {
      throw new HrmsException(ErrorCode.TERMINATED_REACTIVATION);
    }
  }

  /** {@code validate_dept}: {@code -20003}. */
  void requireDepartment(long deptId) {
    if (!employees.departmentActive(deptId)) {
      throw new HrmsException(
          ErrorCode.INVALID_DEPARTMENT, "Invalid or inactive department: " + deptId, "deptId");
    }
  }

  /** {@code validate_job}: {@code -20011}. */
  void requireJob(long jobId) {
    if (!employees.jobActive(jobId)) {
      throw new HrmsException(ErrorCode.INVALID_JOB, "Invalid or inactive job: " + jobId, "jobId");
    }
  }

  void requireLocation(String locationCode) {
    if (!employees.locationActive(locationCode)) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED,
          "Invalid or inactive location: " + locationCode,
          "locationCode");
    }
  }

  /**
   * {@code validate_manager}: the manager must be active ({@code -20004}) and, for an existing
   * subject, must not be the subject or anyone reporting (transitively) to the subject.
   */
  void requireManager(long managerEmpId, @Nullable Long subjectEmpId) {
    if (!employees.managerActive(managerEmpId)) {
      throw new HrmsException(
          ErrorCode.INVALID_MANAGER,
          "Invalid or inactive manager: " + managerEmpId,
          "managerEmpId");
    }
    if (subjectEmpId != null && employees.isInReportingChain(subjectEmpId, managerEmpId)) {
      throw new HrmsException(
          ErrorCode.INVALID_MANAGER,
          "Circular reporting chain detected: Employee "
              + subjectEmpId
              + " cannot report to "
              + managerEmpId,
          "managerEmpId");
    }
  }

  /** {@code TRG_EMP_BEFORE_INSERT/UPDATE}: {@code -20502}, active employees, case-insensitive. */
  void requireEmailFree(String email, @Nullable Long excludeEmpId) {
    if (employees.emailInUse(email, excludeEmpId)) {
      throw emailInUse(email);
    }
  }

  private static HrmsException emailInUse(String email) {
    return new HrmsException(
        ErrorCode.EMAIL_IN_USE, "Email address already in use: " + email, "email");
  }

  /** Unique-index races: the partial e-mail index or {@code uk_employees_number}. */
  private static HrmsException duplicate(DuplicateKeyException e, @Nullable String email) {
    String msg = String.valueOf(e.getMostSpecificCause().getMessage());
    if (msg.contains("uk_employees_email_active") && email != null) {
      return emailInUse(email);
    }
    return new HrmsException(
        ErrorCode.DUPLICATE_EMPLOYEE_NUMBER,
        ErrorCode.DUPLICATE_EMPLOYEE_NUMBER.defaultMessage(),
        null,
        e);
  }

  // ---------------------------------------------------------------- mapping

  private EmployeeDetail detail(EmployeeRow row, CallerIdentity caller) {
    String last4 = EmployeeAccess.canSeeSsn(row.empId(), caller) ? last4(row.ssnEncrypted()) : null;
    return row.toDetail(last4);
  }

  @Nullable
  private String last4(@Nullable String encrypted) {
    if (encrypted == null) {
      return null;
    }
    String ssn;
    try {
      ssn = cipher.decrypt(encrypted);
    } catch (IllegalArgumentException | IllegalStateException e) {
      return null;
    }
    if (ssn == null) {
      return null;
    }
    String digits = ssn.replaceAll("[^0-9]", "");
    return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
  }

  @Nullable
  private static Long toLong(@Nullable Integer v) {
    return v == null ? null : v.longValue();
  }

  /** {@code PKG_EMPLOYEE} stores {@code UPPER(TRIM(first_name / last_name))}. */
  static String legacyName(String name) {
    return name.trim().toUpperCase(Locale.ROOT);
  }

  private static String flag(boolean active) {
    return active ? "Y" : "N";
  }

  /** JSON of the columns that differ between {@code a} and {@code b} (SSN recorded as ***). */
  static String changedColumns(EmployeeRow a, EmployeeRow b, boolean ssnChanged, boolean old) {
    StringBuilder sb = new StringBuilder("{");
    diff(sb, "first_name", a.firstName(), b.firstName(), old);
    diff(sb, "middle_name", a.middleName(), b.middleName(), old);
    diff(sb, "last_name", a.lastName(), b.lastName(), old);
    diff(sb, "date_of_birth", a.dateOfBirth(), b.dateOfBirth(), old);
    diff(sb, "gender", a.gender(), b.gender(), old);
    diff(sb, "marital_status", a.maritalStatus(), b.maritalStatus(), old);
    diff(sb, "nationality", a.nationality(), b.nationality(), old);
    diff(sb, "email", a.email(), b.email(), old);
    diff(sb, "phone_work", a.phoneWork(), b.phoneWork(), old);
    diff(sb, "phone_mobile", a.phoneMobile(), b.phoneMobile(), old);
    diff(sb, "address_line1", a.addressLine1(), b.addressLine1(), old);
    diff(sb, "address_line2", a.addressLine2(), b.addressLine2(), old);
    diff(sb, "city", a.city(), b.city(), old);
    diff(sb, "state_province", a.stateProvince(), b.stateProvince(), old);
    diff(sb, "postal_code", a.postalCode(), b.postalCode(), old);
    diff(sb, "country_code", a.countryCode(), b.countryCode(), old);
    diff(sb, "dept_id", a.deptId(), b.deptId(), old);
    diff(sb, "job_id", a.jobId(), b.jobId(), old);
    diff(sb, "manager_emp_id", a.managerEmpId(), b.managerEmpId(), old);
    diff(sb, "location_code", a.locationCode(), b.locationCode(), old);
    diff(sb, "employment_type", a.employmentType(), b.employmentType(), old);
    diff(sb, "notes", a.notes(), b.notes(), old);
    if (ssnChanged) {
      sb.append(sb.length() > 1 ? "," : "").append("\"ssn\":\"***\"");
    }
    return sb.append('}').toString();
  }

  private static void diff(
      StringBuilder sb, String column, @Nullable Object a, @Nullable Object b, boolean old) {
    if (Objects.equals(a, b)) {
      return;
    }
    Object v = old ? a : b;
    sb.append(sb.length() > 1 ? "," : "")
        .append('"')
        .append(column)
        .append("\":")
        .append(v == null ? "null" : "\"" + String.valueOf(v).replace("\"", "\\\"") + "\"");
  }
}
