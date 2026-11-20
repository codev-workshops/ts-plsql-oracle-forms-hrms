package com.hrms.employee;

import com.hrms.auth.HrmsPrincipal;
import com.hrms.common.ApiException;
import com.hrms.common.AuditWriter;
import com.hrms.common.ErrorCode;
import com.hrms.employee.EmployeeModels.CreateEmployeeRequest;
import com.hrms.employee.EmployeeModels.EmployeeHistoryEntry;
import com.hrms.employee.EmployeeModels.EmployeeMutation;
import com.hrms.employee.EmployeeModels.EmploymentType;
import com.hrms.employee.EmployeeModels.PromoteRequest;
import com.hrms.employee.EmployeeModels.RehireRequest;
import com.hrms.employee.EmployeeModels.TerminateRequest;
import com.hrms.employee.EmployeeModels.TransferRequest;
import com.hrms.employee.EmployeeModels.UpdateEmployeeRequest;
import com.hrms.employee.EmployeeModels.Warning;
import com.hrms.employee.EmployeeModels.WarningCode;
import com.hrms.employee.SalaryRecordPort.ChangeReason;
import com.hrms.employee.SalaryRecordPort.SalaryChange;
import com.hrms.leave.LeaveService;
import com.hrms.notification.NotificationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee core write model: PKG_EMPLOYEE create / update / transfer / promote / terminate / rehire (PKG-E02..E05)
 * with the TRG_EMP_* rules (TRG-001..008) and the HRMS_EMPLOYEE gates (FRM-030..041) re-homed here. History rows
 * (PKG-E04, TRG-007), audit rows (D-08) and notifications (outbox) are written in the business transaction.
 * Compensation goes through {@link SalaryRecordPort} only (OQ-01); payroll code is never called.
 */
@Service
public class EmployeeCommandService {

    static final String TABLE = "EMPLOYEES";
    static final int MAX_HIERARCHY_DEPTH = 15;
    static final int HIRE_DATE_MAX_DAYS_AHEAD = 180;
    static final String TERMINATION_LEAVE_REASON = "Auto-cancelled due to termination";
    private static final long MAX_EMP_NUMBER = 999_999L;
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final JdbcClient jdbc;
    private final EmployeeReadService reads;
    private final SalaryRecordPort salaries;
    private final LeaveService leave;
    private final NotificationService notifications;
    private final AuditWriter audit;
    private final Clock clock;

    public EmployeeCommandService(JdbcClient jdbc, EmployeeReadService reads, SalaryRecordPort salaries,
            LeaveService leave, NotificationService notifications, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.salaries = salaries;
        this.leave = leave;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    /** Caller context for audit rows. */
    public record Caller(HrmsPrincipal principal, String ipAddress) {
        String actor() {
            return principal.actor();
        }
    }

    /** The current row, locked. */
    private record Row(long empId, String empNumber, String firstName, String lastName, String email,
            String phoneWork, String phoneMobile, String addressLine1, String addressLine2, String city,
            String stateProvince, String postalCode, String countryCode, LocalDate hireDate, long deptId, long jobId,
            Long managerEmpId, String locationCode, String employmentStatus, boolean active) {
    }

    // ---------------------------------------------------------------- create (PKG-E03, TRG-001..004, FRM-032)

    @Transactional
    public EmployeeMutation create(Caller caller, CreateEmployeeRequest r) {
        String firstName = trimToNull(r.firstName());
        String lastName = trimToNull(r.lastName());
        if (firstName == null || lastName == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "First name and last name are required", -20010);
        }
        String email = normalizeEmail(r.email());
        String phoneWork = trimToNull(r.phoneWork());
        String phoneMobile = trimToNull(r.phoneMobile());
        requirePhone("phoneWork", phoneWork);
        requirePhone("phoneMobile", phoneMobile);
        if (!EmployeeValidators.isNotFuture(r.dateOfBirth(), today())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "dateOfBirth cannot be in the future");
        }
        validateDept(r.deptId());
        validateManager(r.managerEmpId(), null);
        long gradeId = validateJob(r.jobId());
        List<Warning> warnings = new ArrayList<>();
        if (r.baseSalary() != null) {
            requirePositiveSalary(r.baseSalary());
            salaryWarning(r.baseSalary(), gradeId).ifPresent(warnings::add);
        }
        String location = r.locationCode() != null ? validateLocation(r.locationCode().trim()) : deptLocation(r.deptId());
        if (r.hireDate().isAfter(today().plusDays(HIRE_DATE_MAX_DAYS_AHEAD))) {
            throw new ApiException(ErrorCode.HIRE_DATE_TOO_FAR,
                    "Hire date cannot be more than " + HIRE_DATE_MAX_DAYS_AHEAD + " days in the future");
        }
        requireEmailFree(email, null);

        String empNumber = nextEmpNumber();
        EmploymentType type = r.employmentType() != null ? r.employmentType() : EmploymentType.FULL_TIME;
        String actor = caller.actor();
        OffsetDateTime now = now();
        long empId;
        try {
            empId = jdbc.sql("""
                    INSERT INTO hrms.employees (emp_number, first_name, last_name, email, phone_work, phone_mobile,
                        date_of_birth, hire_date, dept_id, job_id, manager_emp_id, location_code, employment_type,
                        employment_status, active_flag, created_by, created_date)
                    VALUES (:empNumber, :firstName, :lastName, :email, :phoneWork, :phoneMobile, :dob, :hireDate,
                        :deptId, :jobId, :managerEmpId, :location, :type, 'ACTIVE', true, :actor, :now)
                    RETURNING emp_id
                    """).param("empNumber", empNumber).param("firstName", firstName.toUpperCase(Locale.ROOT))
                    .param("lastName", lastName.toUpperCase(Locale.ROOT)).param("email", email)
                    .param("phoneWork", phoneWork).param("phoneMobile", phoneMobile).param("dob", r.dateOfBirth())
                    .param("hireDate", r.hireDate()).param("deptId", r.deptId()).param("jobId", r.jobId())
                    .param("managerEmpId", r.managerEmpId()).param("location", location).param("type", type.name())
                    .param("actor", actor).param("now", now).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }

        if (r.baseSalary() != null) {
            salaries.createSalaryRecord(new SalaryChange(empId, r.hireDate(), r.baseSalary(), ChangeReason.NEW_HIRE,
                    null, actor));
        }
        History h = new History(empId, "HIRE", r.hireDate());
        h.newDept = r.deptId();
        h.newJob = r.jobId();
        h.newManager = r.managerEmpId();
        h.newSalary = r.baseSalary();
        h.newLocation = location;
        writeHistory(h, actor, now);

        Map<String, Object> created = values("empId", empId, "empNumber", empNumber, "firstName",
                firstName.toUpperCase(Locale.ROOT), "lastName", lastName.toUpperCase(Locale.ROOT), "email", email,
                "hireDate", r.hireDate(), "deptId", r.deptId(), "jobId", r.jobId(), "managerEmpId", r.managerEmpId(),
                "locationCode", location, "employmentType", type.name(), "employmentStatus", "ACTIVE");
        audit.write(TABLE, empId, "INSERT", null, created, actor, caller.ipAddress(), caller.principal().sessionId());

        notifications.queueEmail(empId, "Welcome to the Company", "Dear " + firstName + ", Welcome aboard! "
                + "Your employee number is " + empNumber + ".", TABLE, empId, actor);
        if (r.managerEmpId() != null) {
            notifications.queueEmail(r.managerEmpId(), "New Direct Report: " + firstName + " " + lastName,
                    firstName + " " + lastName + " has been added as your direct report, starting "
                            + US_DATE.format(r.hireDate()) + ".", TABLE, empId, actor);
        }
        return result(empId, warnings);
    }

    // ---------------------------------------------------------------- update (PKG-E05 update_employee, FRM-033)

    @Transactional
    public EmployeeMutation update(Caller caller, long empId, UpdateEmployeeRequest r) {
        Row old = lock(empId, false);
        String firstName = upperOrNull("firstName", r.firstName());
        String lastName = upperOrNull("lastName", r.lastName());
        String email = normalizeEmail(r.email());
        requirePhone("phoneWork", r.phoneWork());
        requirePhone("phoneMobile", r.phoneMobile());
        if (email != null && old.active() && !email.equalsIgnoreCase(Objects.toString(old.email(), ""))) {
            requireEmailFree(email, empId);
        }
        Row next = new Row(old.empId(), old.empNumber(), coalesce(firstName, old.firstName()),
                coalesce(lastName, old.lastName()), coalesce(email, old.email()),
                coalesce(r.phoneWork(), old.phoneWork()), coalesce(r.phoneMobile(), old.phoneMobile()),
                coalesce(r.addressLine1(), old.addressLine1()), coalesce(r.addressLine2(), old.addressLine2()),
                coalesce(r.city(), old.city()), coalesce(r.stateProvince(), old.stateProvince()),
                coalesce(r.postalCode(), old.postalCode()), coalesce(r.countryCode(), old.countryCode()),
                old.hireDate(), old.deptId(), old.jobId(), old.managerEmpId(), old.locationCode(),
                old.employmentStatus(), old.active());
        String actor = caller.actor();
        OffsetDateTime now = now();
        try {
            jdbc.sql("""
                    UPDATE hrms.employees SET first_name = :firstName, last_name = :lastName, email = :email,
                        phone_work = :phoneWork, phone_mobile = :phoneMobile, address_line1 = :a1, address_line2 = :a2,
                        city = :city, state_province = :state, postal_code = :postal, country_code = :country,
                        modified_by = :actor, modified_date = :now
                    WHERE emp_id = :empId
                    """).param("firstName", next.firstName()).param("lastName", next.lastName())
                    .param("email", next.email()).param("phoneWork", next.phoneWork())
                    .param("phoneMobile", next.phoneMobile()).param("a1", next.addressLine1())
                    .param("a2", next.addressLine2()).param("city", next.city()).param("state", next.stateProvince())
                    .param("postal", next.postalCode()).param("country", next.countryCode()).param("actor", actor)
                    .param("now", now).param("empId", empId).update();
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        diff(before, after, "firstName", old.firstName(), next.firstName());
        diff(before, after, "lastName", old.lastName(), next.lastName());
        diff(before, after, "email", old.email(), next.email());
        diff(before, after, "phoneWork", old.phoneWork(), next.phoneWork());
        diff(before, after, "phoneMobile", old.phoneMobile(), next.phoneMobile());
        diff(before, after, "addressLine1", old.addressLine1(), next.addressLine1());
        diff(before, after, "addressLine2", old.addressLine2(), next.addressLine2());
        diff(before, after, "city", old.city(), next.city());
        diff(before, after, "stateProvince", old.stateProvince(), next.stateProvince());
        diff(before, after, "postalCode", old.postalCode(), next.postalCode());
        diff(before, after, "countryCode", old.countryCode(), next.countryCode());
        audit.write(TABLE, empId, "UPDATE", before, after, actor, caller.ipAddress(), caller.principal().sessionId());
        return result(empId, List.of());
    }

    // ---------------------------------------------------------------- transfer (PKG-E05 transfer_employee)

    @Transactional
    public EmployeeMutation transfer(Caller caller, long empId, TransferRequest r) {
        Row old = lock(empId, true);
        if (!"ACTIVE".equals(old.employmentStatus())) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ACTIVE,
                    "Cannot transfer non-active employee. Status: " + old.employmentStatus(), -20012);
        }
        validateDept(r.newDeptId());
        long newJob = r.newJobId() != null ? r.newJobId() : old.jobId();
        if (r.newJobId() != null) {
            validateJob(newJob);
        }
        String newLocation = r.newLocationCode() != null ? validateLocation(r.newLocationCode().trim())
                : old.locationCode();
        if (r.newManagerEmpId() != null) {
            validateManager(r.newManagerEmpId(), empId);
        }
        Long newManager = r.newManagerEmpId() != null ? r.newManagerEmpId() : old.managerEmpId();
        LocalDate effective = r.effectiveDate() != null ? r.effectiveDate() : today();
        String actor = caller.actor();
        OffsetDateTime now = now();
        jdbc.sql("""
                UPDATE hrms.employees SET dept_id = :dept, job_id = :job, manager_emp_id = :manager,
                    location_code = :location, modified_by = :actor, modified_date = :now
                WHERE emp_id = :empId
                """).param("dept", r.newDeptId()).param("job", newJob).param("manager", newManager)
                .param("location", newLocation).param("actor", actor).param("now", now).param("empId", empId)
                .update();
        History h = new History(empId, "TRANSFER", effective);
        h.oldDept = old.deptId();
        h.newDept = r.newDeptId();
        h.oldJob = old.jobId();
        h.newJob = newJob;
        h.oldManager = old.managerEmpId();
        h.newManager = newManager;
        h.oldLocation = old.locationCode();
        h.newLocation = newLocation;
        h.reasonCode = r.reasonCode();
        h.comments = r.comments();
        writeHistory(h, actor, now);
        audit.write(TABLE, empId, "UPDATE",
                values("deptId", old.deptId(), "jobId", old.jobId(), "managerEmpId", old.managerEmpId(),
                        "locationCode", old.locationCode()),
                values("deptId", r.newDeptId(), "jobId", newJob, "managerEmpId", newManager, "locationCode",
                        newLocation), actor, caller.ipAddress(), caller.principal().sessionId());
        return result(empId, List.of());
    }

    // ---------------------------------------------------------------- promote (PKG-E05 promote_employee)

    @Transactional
    public EmployeeMutation promote(Caller caller, long empId, PromoteRequest r) {
        Row old = lock(empId, false);
        if (!"ACTIVE".equals(old.employmentStatus())) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ACTIVE,
                    "Cannot promote non-active employee. Status: " + old.employmentStatus());
        }
        long gradeId = validateJob(r.newJobId());
        requirePositiveSalary(r.newSalary());
        List<Warning> warnings = new ArrayList<>();
        salaryWarning(r.newSalary(), gradeId).ifPresent(warnings::add);
        LocalDate effective = r.effectiveDate() != null ? r.effectiveDate() : today();
        BigDecimal oldSalary = salaries.currentBaseSalary(empId, effective).orElse(null);
        BigDecimal changePct = oldSalary != null && oldSalary.signum() > 0
                ? r.newSalary().subtract(oldSalary).multiply(BigDecimal.valueOf(100))
                        .divide(oldSalary, 2, RoundingMode.HALF_UP)
                : null;
        String actor = caller.actor();
        OffsetDateTime now = now();
        jdbc.sql("UPDATE hrms.employees SET job_id = :job, modified_by = :actor, modified_date = :now "
                + "WHERE emp_id = :empId").param("job", r.newJobId()).param("actor", actor).param("now", now)
                .param("empId", empId).update();
        salaries.createSalaryRecord(new SalaryChange(empId, effective, r.newSalary(), ChangeReason.PROMOTION,
                changePct, actor));
        History h = new History(empId, "PROMOTION", effective);
        h.oldJob = old.jobId();
        h.newJob = r.newJobId();
        h.oldSalary = oldSalary;
        h.newSalary = r.newSalary();
        h.comments = r.comments();
        writeHistory(h, actor, now);
        audit.write(TABLE, empId, "UPDATE", values("jobId", old.jobId()), values("jobId", r.newJobId()), actor,
                caller.ipAddress(), caller.principal().sessionId());
        return result(empId, warnings);
    }

    // ---------------------------------------------------------------- terminate (PKG-E05, DEF-22)

    @Transactional
    public EmployeeMutation terminate(Caller caller, long empId, TerminateRequest r) {
        Row old = lock(empId, false);
        if ("TERMINATED".equals(old.employmentStatus())) {
            throw new ApiException(ErrorCode.ALREADY_TERMINATED, "Employee " + empId + " is already terminated");
        }
        checkTransition(old.employmentStatus(), "TERMINATED", false);
        String actor = caller.actor();
        OffsetDateTime now = now();
        leave.cancelPendingRequestsOf(empId, TERMINATION_LEAVE_REASON, actor);
        jdbc.sql("""
                UPDATE hrms.employees SET employment_status = 'TERMINATED', termination_date = :date,
                    termination_reason = :reason, active_flag = false, modified_by = :actor, modified_date = :now
                WHERE emp_id = :empId
                """).param("date", r.terminationDate()).param("reason", r.reason().trim()).param("actor", actor)
                .param("now", now).param("empId", empId).update();
        salaries.endCompensation(empId, r.terminationDate(), actor);
        History h = new History(empId, "TERMINATION", r.terminationDate());
        h.reasonCode = r.reason().trim();
        h.comments = r.comments();
        writeHistory(h, actor, now);
        audit.write(TABLE, empId, "STATUS_CHANGE",
                values("employmentStatus", old.employmentStatus(), "active", old.active()),
                values("employmentStatus", "TERMINATED", "active", false, "terminationDate", r.terminationDate(),
                        "terminationReason", r.reason().trim()), actor, caller.ipAddress(),
                caller.principal().sessionId());
        if (old.managerEmpId() != null) {
            String name = old.firstName() + " " + old.lastName();
            notifications.queueEmail(old.managerEmpId(), "Employee Termination: " + name,
                    name + " termination effective " + US_DATE.format(r.terminationDate()), TABLE, empId, actor);
        }
        return result(empId, List.of());
    }

    // ---------------------------------------------------------------- rehire (PKG-E05, DEF-13)

    @Transactional
    public EmployeeMutation rehire(Caller caller, long empId, RehireRequest r) {
        Row old = lock(empId, false);
        if (!"TERMINATED".equals(old.employmentStatus())) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_TERMINATED,
                    "Only a TERMINATED employee can be rehired. Status: " + old.employmentStatus());
        }
        checkTransition(old.employmentStatus(), "ACTIVE", true);
        validateDept(r.deptId());
        validateManager(r.managerEmpId(), empId);
        long gradeId = validateJob(r.jobId());
        requirePositiveSalary(r.baseSalary());
        List<Warning> warnings = new ArrayList<>();
        salaryWarning(r.baseSalary(), gradeId).ifPresent(warnings::add);
        String location = r.locationCode() != null ? validateLocation(r.locationCode().trim()) : deptLocation(r.deptId());
        requireEmailFree(old.email(), empId);
        String actor = caller.actor();
        OffsetDateTime now = now();
        try {
            jdbc.sql("""
                    UPDATE hrms.employees SET employment_status = 'ACTIVE', hire_date = :date, termination_date = NULL,
                        termination_reason = NULL, dept_id = :dept, job_id = :job, manager_emp_id = :manager,
                        location_code = :location, active_flag = true, modified_by = :actor, modified_date = :now
                    WHERE emp_id = :empId
                    """).param("date", r.rehireDate()).param("dept", r.deptId()).param("job", r.jobId())
                    .param("manager", r.managerEmpId()).param("location", location).param("actor", actor)
                    .param("now", now).param("empId", empId).update();
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        salaries.createSalaryRecord(new SalaryChange(empId, r.rehireDate(), r.baseSalary(), ChangeReason.REHIRE,
                null, actor));
        History h = new History(empId, "REHIRE", r.rehireDate());
        h.oldDept = old.deptId();
        h.newDept = r.deptId();
        h.oldJob = old.jobId();
        h.newJob = r.jobId();
        h.oldManager = old.managerEmpId();
        h.newManager = r.managerEmpId();
        h.newSalary = r.baseSalary();
        h.oldLocation = old.locationCode();
        h.newLocation = location;
        writeHistory(h, actor, now);
        audit.write(TABLE, empId, "STATUS_CHANGE",
                values("employmentStatus", old.employmentStatus(), "active", old.active(), "hireDate",
                        old.hireDate(), "deptId", old.deptId(), "jobId", old.jobId()),
                values("employmentStatus", "ACTIVE", "active", true, "hireDate", r.rehireDate(), "deptId",
                        r.deptId(), "jobId", r.jobId()), actor, caller.ipAddress(), caller.principal().sessionId());
        return result(empId, warnings);
    }

    // ---------------------------------------------------------------- history read

    @Transactional(readOnly = true)
    public List<EmployeeHistoryEntry> history(long empId) {
        if (jdbc.sql("SELECT count(*) FROM hrms.employees WHERE emp_id = :empId").param("empId", empId)
                .query(Long.class).single() == 0) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found");
        }
        return jdbc.sql("""
                SELECT hist_id, change_type, effective_date, old_dept_id, new_dept_id, old_job_id, new_job_id,
                       old_manager_id, new_manager_id, old_salary, new_salary, old_location, new_location,
                       reason_code, comments, created_by, created_date
                FROM hrms.employee_history WHERE emp_id = :empId ORDER BY effective_date, hist_id
                """).param("empId", empId).query((rs, n) -> new EmployeeHistoryEntry(rs.getLong("hist_id"),
                        rs.getString("change_type"), rs.getObject("effective_date", LocalDate.class),
                        rs.getObject("old_dept_id", Long.class), rs.getObject("new_dept_id", Long.class),
                        rs.getObject("old_job_id", Long.class), rs.getObject("new_job_id", Long.class),
                        rs.getObject("old_manager_id", Long.class), rs.getObject("new_manager_id", Long.class),
                        rs.getBigDecimal("old_salary"), rs.getBigDecimal("new_salary"), rs.getString("old_location"),
                        rs.getString("new_location"), rs.getString("reason_code"), rs.getString("comments"),
                        rs.getString("created_by"), rs.getObject("created_date", OffsetDateTime.class)))
                .list();
    }

    // ---------------------------------------------------------------- domain rules

    /**
     * TRG-006: TERMINATED → ACTIVE only through rehire. Every status write goes through this guard; the API has no
     * generic status update, so REACTIVATION_FORBIDDEN is a defensive guarantee (like EMP_NUMBER_CONFLICT).
     */
    static void checkTransition(String from, String to, boolean viaRehire) {
        if ("TERMINATED".equals(from) && "ACTIVE".equals(to) && !viaRehire) {
            throw new ApiException(ErrorCode.REACTIVATION_FORBIDDEN,
                    "Cannot directly reactivate a terminated employee. Use the rehire process.");
        }
    }

    /** PKG_EMPLOYEE.validate_dept (-20003, FRM-036). */
    void validateDept(long deptId) {
        if (jdbc.sql("SELECT count(*) FROM hrms.departments WHERE dept_id = :id AND active_flag").param("id", deptId)
                .query(Long.class).single() == 0) {
            throw new ApiException(ErrorCode.INVALID_DEPARTMENT, "Invalid or inactive department: " + deptId);
        }
    }

    /** Active job (-20011, FRM-037); returns its grade. */
    long validateJob(long jobId) {
        return jdbc.sql("SELECT grade_id FROM hrms.job_titles WHERE job_id = :id AND active_flag").param("id", jobId)
                .query(Long.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_JOB, "Invalid or inactive job: " + jobId));
    }

    /**
     * PKG_EMPLOYEE.validate_manager (-20004, FRM-040): the manager must be ACTIVE; when an employee id is given,
     * walk up the manager chain at most {@value #MAX_HIERARCHY_DEPTH} levels and reject a chain that reaches the
     * employee.
     */
    void validateManager(Long managerId, Long empId) {
        if (managerId == null) {
            return;
        }
        if (jdbc.sql("SELECT count(*) FROM hrms.employees WHERE emp_id = :id AND employment_status = 'ACTIVE'")
                .param("id", managerId).query(Long.class).single() == 0) {
            throw new ApiException(ErrorCode.INVALID_MANAGER, "Invalid or inactive manager: " + managerId);
        }
        if (empId == null) {
            return;
        }
        Long current = managerId;
        for (int depth = 0; current != null && depth < MAX_HIERARCHY_DEPTH; depth++) {
            if (current.longValue() == empId) {
                throw new ApiException(ErrorCode.CIRCULAR_REPORTING, "Circular reporting chain detected: Employee "
                        + empId + " cannot report to " + managerId);
            }
            current = jdbc.sql("SELECT manager_emp_id FROM hrms.employees WHERE emp_id = :id").param("id", current)
                    .query((rs, n) -> rs.getObject(1, Long.class)).optional().orElse(null);
        }
    }

    private String validateLocation(String code) {
        if (jdbc.sql("SELECT count(*) FROM hrms.locations WHERE location_code = :c AND active_flag").param("c", code)
                .query(Long.class).single() == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown or inactive location: " + code);
        }
        return code;
    }

    private String deptLocation(long deptId) {
        return jdbc.sql("SELECT location_code FROM hrms.departments WHERE dept_id = :id").param("id", deptId)
                .query(String.class).single();
    }

    /** TRG-004 (-20502): email unique case-insensitively among active rows. */
    private void requireEmailFree(String email, Long exceptEmpId) {
        if (email == null) {
            return;
        }
        if (jdbc.sql("SELECT count(*) FROM hrms.employees WHERE lower(email) = lower(:email) AND active_flag "
                + "AND emp_id <> :except").param("email", email)
                .param("except", exceptEmpId == null ? -1L : exceptEmpId).query(Long.class).single() > 0) {
            throw new ApiException(ErrorCode.EMAIL_IN_USE, "Email address already in use: " + email);
        }
    }

    /** OQ-11: outside the grade range is a warning, never an error (PKG_VALIDATION.validate_salary_for_grade). */
    private Optional<Warning> salaryWarning(BigDecimal salary, long gradeId) {
        return jdbc.sql("SELECT min_salary, max_salary, grade_name FROM hrms.job_grades WHERE grade_id = :id")
                .param("id", gradeId).query((rs, n) -> {
                    BigDecimal min = rs.getBigDecimal(1);
                    BigDecimal max = rs.getBigDecimal(2);
                    String grade = rs.getString(3);
                    if (min != null && salary.compareTo(min) < 0) {
                        return new Warning(WarningCode.SALARY_OUTSIDE_GRADE, "Salary " + money(salary)
                                + " is below minimum for grade " + grade + " (" + money(min) + ")");
                    }
                    if (max != null && salary.compareTo(max) > 0) {
                        return new Warning(WarningCode.SALARY_OUTSIDE_GRADE, "Salary " + money(salary)
                                + " exceeds maximum for grade " + grade + " (" + money(max) + ")");
                    }
                    return null;
                }).list().stream().filter(Objects::nonNull).findFirst();
    }

    private static void requirePositiveSalary(BigDecimal salary) {
        if (salary.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_SALARY, "Base salary must be greater than zero");
        }
    }

    private static void requirePhone(String field, String phone) {
        if (!EmployeeValidators.isValidPhone(phone)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " must have 10 or 11 digits");
        }
    }

    private static String normalizeEmail(String email) {
        String e = trimToNull(email);
        if (e == null) {
            return null;
        }
        if (!EmployeeValidators.isValidEmail(e)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid email format");
        }
        return e.toLowerCase(Locale.ROOT);
    }

    /** D-02: EMP-NNNNNN from hrms.seq_emp_number; overflow past 999999 is an error, not a silent widening. */
    private String nextEmpNumber() {
        long n = jdbc.sql("SELECT nextval('hrms.seq_emp_number')").query(Long.class).single();
        if (n > MAX_EMP_NUMBER) {
            throw new IllegalStateException("Employee number sequence exhausted");
        }
        return String.format("EMP-%06d", n);
    }

    private static ApiException duplicate(DuplicateKeyException e) {
        String m = String.valueOf(e.getMostSpecificCause().getMessage());
        if (m.contains("uq_employees_email_active")) {
            return new ApiException(ErrorCode.EMAIL_IN_USE, "Email address already in use");
        }
        if (m.contains("uk_emp_number")) {
            return new ApiException(ErrorCode.EMP_NUMBER_CONFLICT, "Duplicate employee number generated. Please retry.");
        }
        throw e;
    }

    // ---------------------------------------------------------------- helpers

    private Row lock(long empId, boolean noWait) {
        return jdbc.sql("SELECT * FROM hrms.employees WHERE emp_id = :empId FOR UPDATE" + (noWait ? " NOWAIT" : ""))
                .param("empId", empId).query(EmployeeCommandService::row).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found: " + empId));
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getLong("emp_id"), rs.getString("emp_number"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("email"), rs.getString("phone_work"),
                rs.getString("phone_mobile"), rs.getString("address_line1"), rs.getString("address_line2"),
                rs.getString("city"), rs.getString("state_province"), rs.getString("postal_code"),
                rs.getString("country_code"), rs.getObject("hire_date", LocalDate.class), rs.getLong("dept_id"),
                rs.getLong("job_id"), rs.getObject("manager_emp_id", Long.class), rs.getString("location_code"),
                rs.getString("employment_status"), rs.getBoolean("active_flag"));
    }

    /** One EMPLOYEE_HISTORY row in the log_history shape (PKG-E04, TRG-007, DEF-14), in the caller's transaction. */
    private static final class History {
        final long empId;
        final String changeType;
        final LocalDate effectiveDate;
        Long oldDept;
        Long newDept;
        Long oldJob;
        Long newJob;
        Long oldManager;
        Long newManager;
        BigDecimal oldSalary;
        BigDecimal newSalary;
        String oldLocation;
        String newLocation;
        String reasonCode;
        String comments;

        History(long empId, String changeType, LocalDate effectiveDate) {
            this.empId = empId;
            this.changeType = changeType;
            this.effectiveDate = effectiveDate;
        }
    }

    private void writeHistory(History h, String actor, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO hrms.employee_history (emp_id, change_type, effective_date, old_dept_id, new_dept_id,
                    old_job_id, new_job_id, old_manager_id, new_manager_id, old_salary, new_salary, old_location,
                    new_location, reason_code, comments, created_by, created_date)
                VALUES (:empId, :type, :effective, :oldDept, :newDept, :oldJob, :newJob, :oldManager, :newManager,
                    :oldSalary, :newSalary, :oldLocation, :newLocation, :reason, :comments, :actor, :now)
                """).param("empId", h.empId).param("type", h.changeType).param("effective", h.effectiveDate)
                .param("oldDept", h.oldDept).param("newDept", h.newDept).param("oldJob", h.oldJob)
                .param("newJob", h.newJob).param("oldManager", h.oldManager).param("newManager", h.newManager)
                .param("oldSalary", h.oldSalary).param("newSalary", h.newSalary).param("oldLocation", h.oldLocation)
                .param("newLocation", h.newLocation).param("reason", h.reasonCode).param("comments", h.comments)
                .param("actor", actor).param("now", now).update();
    }

    private EmployeeMutation result(long empId, List<Warning> warnings) {
        return new EmployeeMutation(reads.findById(empId, true).orElseThrow(), List.copyOf(warnings));
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void diff(Map<String, Object> before, Map<String, Object> after, String key, Object o, Object n) {
        if (!Objects.equals(o, n)) {
            before.put(key, o);
            after.put(key, n);
        }
    }

    private static String upperOrNull(String field, String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " cannot be blank", -20010);
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static <T> T coalesce(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static String money(BigDecimal v) {
        return "$" + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
