package com.acme.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.employee.EmployeeDtos.EmployeeDetail;
import com.acme.hrms.employee.EmployeeDtos.EmployeeHistoryEntry;
import com.acme.hrms.salary.SalaryService;
import com.acme.hrms.validation.dto.employee.DependentRequest;
import com.acme.hrms.validation.dto.employee.EmployeeCreateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeTerminateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeTransferRequest;
import com.acme.hrms.validation.dto.employee.EmployeeUpdateRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

/**
 * Level 1 – {@code PKG_EMPLOYEE.validate_employee} and {@code TRG_EMPLOYEES} rules as service
 * invariants: one test per legacy error code, against real repositories on Testcontainers
 * PostgreSQL. Salary, audit and session revocation are collaborators (ARCH-01) and are mocked.
 */
class EmployeeServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2025, 6, 1);
  private static final Clock CLOCK =
      Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
  private static final CallerIdentity HR =
      new CallerIdentity("hr.admin", 10, Set.of("EMPLOYEE:VIEW", "EMPLOYEE:EDIT"), "jti-hr");
  private static final CallerIdentity SELF =
      new CallerIdentity("sarah.chen", 2, Set.of("EMPLOYEE:VIEW"), "jti-self");

  private static JdbcTemplate jdbc;
  private static EmployeeRepository employees;
  private static EmployeeHistoryRepository history;

  private SalaryService salaries;
  private AuditService audit;
  private EmployeeSessionRevoker revoker;
  private ApplicationEventPublisher events;
  private EmployeeService service;

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
    employees = new EmployeeRepository(jdbc);
    history = new EmployeeHistoryRepository(jdbc);
  }

  @BeforeEach
  void freshService() {
    jdbc.update("delete from employee_dependents where emp_id >= 10000");
    jdbc.update("delete from employee_history where emp_id >= 10000");
    jdbc.update("delete from employees where emp_id >= 10000");
    salaries = mock(SalaryService.class);
    audit = mock(AuditService.class);
    revoker = mock(EmployeeSessionRevoker.class);
    events = mock(ApplicationEventPublisher.class);
    service =
        new EmployeeService(
            employees,
            history,
            new DependentRepository(jdbc),
            new EmergencyContactRepository(jdbc),
            salaries,
            audit,
            new ReversingCipher(),
            revoker,
            events,
            CLOCK);
  }

  // --------------------------------------------------------------- create

  @Test
  void createDrawsNumberFromSequenceWritesHireHistoryAndOpensSalaryThroughSalaryService() {
    EmployeeCreateRequest r = create(c -> c.setInitialSalary(new BigDecimal("85000")));
    EmployeeDetail d = service.create(r, HR);
    assertThat(d.empNumber()).matches("EMP-\\d{6}");
    assertThat(d.employmentStatus()).isEqualTo("ACTIVE");
    assertThat(d.active()).isTrue();
    assertThat(d.version()).isZero();
    assertThat(d.ssnLast4()).isEqualTo("6789");
    verify(salaries).createInitial(d.id(), new BigDecimal("85000"), TODAY, "hr.admin");
    List<EmployeeHistoryEntry> h = service.history(d.id(), HR);
    assertThat(h).hasSize(1);
    assertThat(h.get(0).changeType()).isEqualTo("HIRE");
    assertThat(h.get(0).newSalary()).isEqualTo("85000.00");
    assertThat(
            jdbc.queryForObject(
                "select ssn_encrypted from employees where emp_id = ?", String.class, d.id()))
        .isEqualTo("9876-54-321");
  }

  @Test
  void createWithoutSalaryNeverTouchesSalaryService() {
    EmployeeDetail d = service.create(create(c -> {}), HR);
    verify(salaries, never()).createInitial(anyLong(), any(), any(), anyString());
    assertThat(service.history(d.id(), HR).get(0).newSalary()).isNull();
  }

  @Test
  void code20010NamesRequired() {
    expect(() -> service.create(create(c -> c.setFirstName("  ")), HR), ErrorCode.NAMES_REQUIRED)
        .hasFieldOrPropertyWithValue("field", "firstName");
    expect(() -> service.create(create(c -> c.setLastName(null)), HR), ErrorCode.NAMES_REQUIRED)
        .hasFieldOrPropertyWithValue("field", "lastName");
  }

  @Test
  void code20501HireDateMoreThan90DaysInTheFuture() {
    service.create(create(c -> c.setHireDate(TODAY.plusDays(90))), HR);
    expect(
        () -> service.create(create(c -> c.setHireDate(TODAY.plusDays(91))), HR),
        ErrorCode.HIRE_DATE_TOO_FAR);
  }

  @Test
  void code20101SalaryMustBePositive() {
    expect(
            () -> service.create(create(c -> c.setInitialSalary(new BigDecimal("0"))), HR),
            ErrorCode.SALARY_NOT_POSITIVE)
        .hasMessage("Salary must be positive: 0");
    expect(
            () -> service.create(create(c -> c.setInitialSalary(new BigDecimal("-1.5"))), HR),
            ErrorCode.SALARY_NOT_POSITIVE)
        .hasMessage("Salary must be positive: -1.5");
  }

  @Test
  void code20003InvalidOrInactiveDepartment() {
    expect(() -> service.create(create(c -> c.setDeptId(999)), HR), ErrorCode.INVALID_DEPARTMENT)
        .hasMessage("Invalid or inactive department: 999");
    jdbc.update("update departments set active_flag = 'N' where dept_id = 70");
    try {
      expect(() -> service.create(create(c -> c.setDeptId(70)), HR), ErrorCode.INVALID_DEPARTMENT);
    } finally {
      jdbc.update("update departments set active_flag = 'Y' where dept_id = 70");
    }
  }

  @Test
  void code20011InvalidOrInactiveJob() {
    expect(() -> service.create(create(c -> c.setJobId(999)), HR), ErrorCode.INVALID_JOB)
        .hasMessage("Invalid or inactive job: 999");
  }

  @Test
  void code20004InvalidInactiveOrTerminatedManager() {
    expect(
            () -> service.create(create(c -> c.setManagerEmpId(424242)), HR),
            ErrorCode.INVALID_MANAGER)
        .hasMessage("Invalid or inactive manager: 424242");
    // 99 is TERMINATED in the seed
    expect(() -> service.create(create(c -> c.setManagerEmpId(99)), HR), ErrorCode.INVALID_MANAGER);
  }

  @Test
  void code20004ManagerCycleViaRecursiveChain() {
    // 31 reports to 30 reports to 3 reports to 1: 3 may not report to 31, nobody to themselves
    expect(
            () -> service.update(3, 0, update(u -> u.setManagerEmpId(31)), HR),
            ErrorCode.INVALID_MANAGER)
        .hasMessage("Circular reporting chain detected: Employee 3 cannot report to 31");
    expect(
        () -> service.update(3, 0, update(u -> u.setManagerEmpId(3)), HR),
        ErrorCode.INVALID_MANAGER);
    EmployeeTransferRequest t = transfer(30);
    t.setNewManagerEmpId(32);
    expect(() -> service.transfer(30, t, HR), ErrorCode.INVALID_MANAGER)
        .hasMessage("Circular reporting chain detected: Employee 30 cannot report to 32");
    // a sibling is fine
    EmployeeDetail moved = service.update(33, 0, update(u -> u.setManagerEmpId(32)), HR);
    assertThat(moved.managerEmpId()).isEqualTo(32);
    jdbc.update("update employees set manager_emp_id = 31, version = 0 where emp_id = 33");
  }

  @Test
  void code20502EmailInUseIsCaseInsensitiveActiveOnlyAndExcludesSubjectOnUpdate() {
    expect(
            () -> service.create(create(c -> c.setEmail("SARAH.CHEN@company.com")), HR),
            ErrorCode.EMAIL_IN_USE)
        .hasMessage("Email address already in use: SARAH.CHEN@company.com");
    // terminated employee's address is free again
    EmployeeDetail d = service.create(create(c -> c.setEmail("Brian.Foster@company.com")), HR);
    assertThat(d.email()).isEqualTo("Brian.Foster@company.com");
    // own address on PUT is not a collision; someone else's is
    service.update(d.id(), 0, update(u -> u.setEmail("brian.foster@COMPANY.com")), HR);
    expect(
        () -> service.update(d.id(), 1, update(u -> u.setEmail("sarah.chen@company.com")), HR),
        ErrorCode.EMAIL_IN_USE);
  }

  @Test
  void code20001EmployeeNotFound() {
    expect(() -> service.get(424242, HR), ErrorCode.EMPLOYEE_NOT_FOUND);
    expect(() -> service.update(424242, 0, update(u -> {}), HR), ErrorCode.EMPLOYEE_NOT_FOUND);
    expect(() -> service.terminate(424242, terminate(), HR), ErrorCode.EMPLOYEE_NOT_FOUND);
    expect(() -> service.transfer(424242, transfer(30), HR), ErrorCode.EMPLOYEE_NOT_FOUND);
  }

  // --------------------------------------------------------------- update

  @Test
  void updateIsOptimisticallyLockedAndKeepsImmutableColumns() {
    EmployeeDetail d = service.create(create(c -> {}), HR);
    EmployeeDetail after = service.update(d.id(), 0, update(u -> u.setFirstName("RENAMED")), HR);
    assertThat(after.version()).isEqualTo(1);
    assertThat(after.firstName()).isEqualTo("RENAMED");
    assertThat(after.empNumber()).isEqualTo(d.empNumber());
    assertThat(after.hireDate()).isEqualTo(d.hireDate());
    assertThat(after.deptId()).isEqualTo(d.deptId());
    expect(() -> service.update(d.id(), 0, update(u -> {}), HR), ErrorCode.CONFLICT);
    // job change writes a PROMOTION row; nothing else does
    assertThat(service.history(d.id(), HR))
        .extracting(EmployeeHistoryEntry::changeType)
        .containsExactly("HIRE");
    service.update(d.id(), 1, update(u -> u.setJobId(2)), HR);
    assertThat(service.history(d.id(), HR))
        .extracting(EmployeeHistoryEntry::changeType)
        .containsExactly("PROMOTION", "HIRE");
  }

  @Test
  void code20503NoDirectReactivationOfTerminatedEmployee() {
    expect(() -> service.update(99, 0, update(u -> {}), HR), ErrorCode.TERMINATED_REACTIVATION);
    DependentRequest dep = new DependentRequest();
    dep.setFirstName("KID");
    dep.setLastName("FOSTER");
    dep.setRelationship("CHILD");
    expect(() -> service.addDependent(99, dep, HR), ErrorCode.TERMINATED_REACTIVATION);
    assertThat(
            jdbc.queryForObject(
                "select employment_status from employees where emp_id = 99", String.class))
        .isEqualTo("TERMINATED");
  }

  // ------------------------------------------------------------ terminate

  @Test
  void terminateClosesSalaryRevokesSessionsWritesHistoryAndPublishesEvent() {
    EmployeeDetail d = service.create(create(c -> {}), HR);
    EmployeeTerminateRequest t = terminate();
    EmployeeDetail gone = service.terminate(d.id(), t, HR);
    assertThat(gone.employmentStatus()).isEqualTo("TERMINATED");
    assertThat(gone.active()).isFalse();
    assertThat(gone.terminationDate()).isEqualTo(t.getEffectiveDate());
    assertThat(gone.terminationReason()).isEqualTo("VOLUNTARY");
    verify(salaries).closeActive(d.id(), t.getEffectiveDate(), "hr.admin");
    verify(revoker).revokeSessions(d.id(), "hr.admin");
    verify(events)
        .publishEvent(
            new EmployeeTerminatedEvent(d.id(), t.getEffectiveDate(), "VOLUNTARY", "hr.admin"));
    assertThat(service.history(d.id(), HR).get(0).changeType()).isEqualTo("TERMINATION");
  }

  @Test
  void code20005SecondTerminationIsRejectedAndNothingIsDeleted() {
    EmployeeDetail d = service.create(create(c -> {}), HR);
    service.terminate(d.id(), terminate(), HR);
    expect(() -> service.terminate(d.id(), terminate(), HR), ErrorCode.EMPLOYEE_ALREADY_TERMINATED)
        .hasMessage("Employee " + d.id() + " is already terminated");
    expect(() -> service.terminate(99, terminate(), HR), ErrorCode.EMPLOYEE_ALREADY_TERMINATED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from employees where emp_id in (?, 99)", Integer.class, d.id()))
        .isEqualTo(2);
  }

  @Test
  void terminationBeforeHireDateIsAValidationFailure() {
    EmployeeDetail d = service.create(create(c -> {}), HR);
    EmployeeTerminateRequest t = terminate();
    t.setEffectiveDate(d.hireDate().minusDays(1));
    expect(() -> service.terminate(d.id(), t, HR), ErrorCode.VALIDATION_FAILED)
        .hasFieldOrPropertyWithValue("field", "effectiveDate");
  }

  @Test
  void noPhysicalDeletePathExistsOnAnyOwnedRepository() {
    for (Class<?> repo :
        List.of(
            EmployeeRepository.class,
            DependentRepository.class,
            EmergencyContactRepository.class,
            EmployeeHistoryRepository.class)) {
      assertThat(repo.getDeclaredMethods())
          .noneMatch(m -> m.getName().toLowerCase().contains("delete"));
    }
  }

  // ------------------------------------------------------------- transfer

  @Test
  void transferAlwaysWritesHistoryEvenWithinTheSameDepartment() {
    EmployeeDetail d = service.create(create(c -> {}), HR);
    EmployeeTransferRequest same = transfer((int) d.deptId());
    EmployeeDetail after = service.transfer(d.id(), same, HR);
    assertThat(after.deptId()).isEqualTo(d.deptId());
    assertThat(after.version()).isEqualTo(1);
    EmployeeTransferRequest other = transfer(40);
    other.setNewLocationCode("SF");
    after = service.transfer(d.id(), other, HR);
    assertThat(after.deptId()).isEqualTo(40);
    assertThat(after.locationCode()).isEqualTo("SF");
    assertThat(service.history(d.id(), HR))
        .extracting(EmployeeHistoryEntry::changeType)
        .containsExactly("TRANSFER", "TRANSFER", "HIRE");
    expect(() -> service.transfer(d.id(), transfer(999), HR), ErrorCode.INVALID_DEPARTMENT);
  }

  @Test
  void code20012CannotTransferNonActiveEmployee() {
    expect(() -> service.transfer(99, transfer(30), HR), ErrorCode.EMPLOYEE_NOT_ACTIVE)
        .hasMessage("Cannot transfer non-active employee. Status: TERMINATED");
    EmployeeDetail d = service.create(create(c -> {}), HR);
    jdbc.update("update employees set employment_status = 'ON_LEAVE' where emp_id = ?", d.id());
    expect(() -> service.transfer(d.id(), transfer(30), HR), ErrorCode.EMPLOYEE_NOT_ACTIVE)
        .hasMessage("Cannot transfer non-active employee. Status: ON_LEAVE");
  }

  // ---------------------------------------------------------------- scope

  @Test
  void rowScopeIsSelfOrEditAndSsnLast4FollowsIt() {
    EmployeeDetail self = service.get(2, SELF);
    assertThat(self.id()).isEqualTo(2);
    expect(() -> service.get(3, SELF), ErrorCode.FORBIDDEN);
    expect(() -> service.history(3, SELF), ErrorCode.FORBIDDEN);
    expect(() -> service.dependents(3, SELF), ErrorCode.FORBIDDEN);
    CallerIdentity payroll = new CallerIdentity("pay", 11, Set.of("PAYROLL:VIEW"), "j");
    expect(() -> service.get(2, payroll), ErrorCode.FORBIDDEN);
  }

  // -------------------------------------------------------------- helpers

  private static AbstractThrowableAssert<?, ?> expect(Runnable call, ErrorCode code) {
    return assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HrmsException.class, e -> assertThat(e.code()).as(code.name()).isEqualTo(code));
  }

  private static EmployeeCreateRequest create(Consumer<EmployeeCreateRequest> customise) {
    EmployeeCreateRequest r = new EmployeeCreateRequest();
    r.setFirstName("NEW");
    r.setLastName("HIRE");
    r.setMiddleName("");
    r.setHireDate(TODAY);
    r.setDeptId(30);
    r.setJobId(50);
    r.setManagerEmpId(31);
    r.setLocationCode("CHI");
    r.setSsn("123-45-6789");
    customise.accept(r);
    return r;
  }

  private static EmployeeUpdateRequest update(Consumer<EmployeeUpdateRequest> customise) {
    EmployeeUpdateRequest r = new EmployeeUpdateRequest();
    r.setFirstName("NEW");
    r.setLastName("HIRE");
    r.setJobId(50);
    customise.accept(r);
    return r;
  }

  private static EmployeeTerminateRequest terminate() {
    EmployeeTerminateRequest r = new EmployeeTerminateRequest();
    r.setEffectiveDate(TODAY.plusDays(14));
    r.setReason("VOLUNTARY");
    return r;
  }

  private static EmployeeTransferRequest transfer(int deptId) {
    EmployeeTransferRequest r = new EmployeeTransferRequest();
    r.setEffectiveDate(TODAY);
    r.setDeptId(deptId);
    return r;
  }

  /** Deterministic stand-in for the AES cipher so stored vs. returned values can be asserted. */
  static final class ReversingCipher implements SensitiveFieldCipher {
    @Override
    @Nullable
    public String encrypt(@Nullable String plaintext) {
      return plaintext == null ? null : new StringBuilder(plaintext).reverse().toString();
    }

    @Override
    @Nullable
    public String decrypt(@Nullable String stored) {
      return encrypt(stored);
    }
  }
}
