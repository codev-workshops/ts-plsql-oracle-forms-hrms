package com.hrms.employee;

import com.hrms.employee.EmployeeModels.Dependent;
import com.hrms.employee.EmployeeModels.EmergencyContact;
import com.hrms.employee.EmployeeModels.EmployeeCreate;
import com.hrms.employee.EmployeeModels.EmployeeDetail;
import com.hrms.employee.EmployeeModels.EmployeeHistory;
import com.hrms.employee.EmployeeModels.EmployeeRow;
import com.hrms.employee.EmployeeModels.EmployeeUpdate;
import com.hrms.employee.EmployeeModels.HistoryEntry;
import com.hrms.employee.EmployeeModels.JobRef;
import com.hrms.employee.EmployeeModels.OrgChartNode;
import com.hrms.employee.EmployeeModels.PromoteRequest;
import com.hrms.employee.EmployeeModels.RehireRequest;
import com.hrms.employee.EmployeeModels.TerminateRequest;
import com.hrms.employee.EmployeeModels.TransferRequest;
import com.hrms.employee.EmployeeRepository.NewEmployee;
import com.hrms.employee.EmployeeRepository.SearchFilter;
import com.hrms.leave.LeaveModels.LeaveRequest;
import com.hrms.leave.LeaveRepository;
import com.hrms.leave.LeaveService;
import com.hrms.platform.audit.AuditService;
import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.employee.EmployeeSummary;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.notification.NotificationService;
import com.hrms.platform.salary.SalaryRecord;
import com.hrms.platform.salary.SalaryRecordService;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import com.hrms.platform.validation.Validators;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeService {

    static final int MAX_FUTURE_HIRE_DAYS = 90;
    static final int MAX_SEARCH_LIMIT = 1000;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "ON_LEAVE", "SUSPENDED", "TERMINATED");
    private static final Set<String> EMPLOYMENT_TYPES = Set.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERN");
    private static final Set<String> GENDERS = Set.of("M", "F", "O");
    private static final Set<String> RELATIONSHIPS = Set.of("SPOUSE", "CHILD", "PARENT", "DOMESTIC_PARTNER", "OTHER");

    private final EmployeeRepository repo;
    private final EmployeeLookup lookup;
    private final SalaryRecordService salaries;
    private final LeaveRepository leaveRepository;
    private final LeaveService leaveService;
    private final PermissionService permissions;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public EmployeeService(EmployeeRepository repo, EmployeeLookup lookup, SalaryRecordService salaries,
                           LeaveRepository leaveRepository, LeaveService leaveService, PermissionService permissions,
                           AuditService audit, NotificationService notifications, Clock clock) {
        this.repo = repo;
        this.lookup = lookup;
        this.salaries = salaries;
        this.leaveRepository = leaveRepository;
        this.leaveService = leaveService;
        this.permissions = permissions;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ---- queries ----

    public List<EmployeeSummary> search(CurrentUser user, String lastName, String firstName, Long deptId,
                                        Long locationId, String status, LocalDate hiredFrom, LocalDate hiredTo,
                                        Integer limit) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION, "Unknown employment status: " + status);
        }
        int max = limit == null ? 100 : Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        return repo.search(new SearchFilter(blankToNull(lastName), blankToNull(firstName), deptId, locationId,
                status, hiredFrom, hiredTo, max));
    }

    public EmployeeDetail get(CurrentUser user, long empId) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        return detail(user, load(empId), List.of());
    }

    public EmployeeDetail getByNumber(CurrentUser user, String empNumber) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        EmployeeRow row = repo.findByNumber(empNumber.trim().toUpperCase())
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Employee not found: " + empNumber));
        return detail(user, row, List.of());
    }

    public List<EmployeeSummary> directReports(CurrentUser user, long empId) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        load(empId);
        return lookup.directReports(empId);
    }

    public List<OrgChartNode> orgChart(CurrentUser user, long empId) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        load(empId);
        return repo.orgChart(empId);
    }

    public List<EmployeeHistory> history(CurrentUser user, long empId) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        load(empId);
        return repo.history(empId, canSeeSensitive(user, empId));
    }

    public List<SalaryRecord> salaryHistory(CurrentUser user, long empId) {
        load(empId);
        requireSensitive(user, empId);
        return salaries.history(empId);
    }

    // ---- lifecycle ----

    @Transactional
    public EmployeeDetail create(CurrentUser user, EmployeeCreate req) {
        permissions.require(user, Module.EMPLOYEE, Action.CREATE);
        String first = requireName(req.firstName(), "First name");
        String last = requireName(req.lastName(), "Last name");
        String email = normalizeEmail(req.email());
        LocalDate today = LocalDate.now(clock);
        validateHireDate(req.hireDate(), today);
        if (req.dateOfBirth() != null && !req.dateOfBirth().isBefore(today)) {
            throw new ApiException(ErrorCode.VALIDATION, "Date of birth must be in the past");
        }
        String gender = normalizeGender(req.gender());
        String type = req.employmentType() == null ? "FULL_TIME" : req.employmentType();
        if (!EMPLOYMENT_TYPES.contains(type)) {
            throw new ApiException(ErrorCode.VALIDATION, "Unsupported employment type: " + type);
        }
        validatePhone(req.phoneWork());
        validatePhone(req.phoneMobile());
        requireDepartment(req.deptId());
        JobRef job = requireJob(req.jobId());
        validateManager(req.managerEmpId(), null);
        String location = req.locationId() != null ? requireLocation(req.locationId())
                : repo.departmentLocation(req.deptId()).orElse(null);
        if (repo.activeEmailTaken(email, null)) {
            throw new ApiException(ErrorCode.EMPLOYEE_DUPLICATE_EMAIL, "Email already in use: " + email);
        }
        long empId;
        try {
            empId = repo.insert(new NewEmployee(repo.nextEmpNumber(), first, trimToUpper(req.middleName()), last,
                    email, blankToNull(req.phoneWork()), blankToNull(req.phoneMobile()), req.dateOfBirth(), gender,
                    trimToUpper(req.maritalStatus()), req.hireDate(), req.deptId(), req.jobId(),
                    req.managerEmpId(), location, type), user.username());
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        List<String> warnings = new ArrayList<>();
        if (req.baseSalary() != null) {
            salaries.changeSalary(empId, req.baseSalary(), req.hireDate(), "NEW_HIRE", null, user.username());
            salaryWarning(req.baseSalary(), job).ifPresent(warnings::add);
        }
        repo.insertHistory(new HistoryEntry(empId, "HIRE", req.hireDate(), null, req.deptId(), null, req.jobId(),
                null, req.managerEmpId(), null, req.baseSalary(), null, location, "NEW_HIRE", null),
                user.username());
        EmployeeRow created = load(empId);
        audit.record("EMPLOYEES", empId, AuditService.Action.INSERT, null, auditValues(created), user.username());
        notifications.queue(empId, "Welcome to the company",
                "Welcome " + created.fullName() + ", your employee number is " + created.empNumber() + ".",
                "EMPLOYEES", empId, user.username());
        if (req.managerEmpId() != null) {
            notifications.queue(req.managerEmpId(), "New direct report",
                    created.fullName() + " joins your team on " + req.hireDate() + ".", "EMPLOYEES", empId,
                    user.username());
        }
        return detail(user, created, warnings);
    }

    @Transactional
    public EmployeeDetail update(CurrentUser user, long empId, EmployeeUpdate req) {
        EmployeeRow current = lockOrThrow(empId);
        if (user.empId() != empId) {
            permissions.require(user, Module.EMPLOYEE, Action.EDIT);
        }
        String first = req.firstName() == null ? current.firstName() : requireName(req.firstName(), "First name");
        String last = req.lastName() == null ? current.lastName() : requireName(req.lastName(), "Last name");
        String email = current.email();
        if (req.email() != null) {
            email = normalizeEmail(req.email());
            if (current.active() && repo.activeEmailTaken(email, empId)) {
                throw new ApiException(ErrorCode.EMPLOYEE_DUPLICATE_EMAIL, "Email already in use: " + email);
            }
        }
        validatePhone(req.phoneWork());
        validatePhone(req.phoneMobile());
        EmployeeRow updated = new EmployeeRow(current.empId(), current.empNumber(), first,
                req.middleName() == null ? current.middleName() : trimToUpper(req.middleName()), last, email,
                current.deptId(), current.deptName(), current.jobId(), current.jobTitle(), current.gradeLevel(),
                current.managerEmpId(), current.locationCode(), current.locationId(), current.hireDate(),
                current.employmentStatus(), current.active(), current.dateOfBirth(), current.gender(),
                current.maritalStatus(), patch(req.phoneWork(), current.phoneWork()),
                patch(req.phoneMobile(), current.phoneMobile()), patch(req.addressLine1(), current.addressLine1()),
                patch(req.addressLine2(), current.addressLine2()), patch(req.city(), current.city()),
                patch(req.state(), current.state()), patch(req.postalCode(), current.postalCode()),
                req.country() == null ? current.country() : trimToUpper(req.country()),
                current.employmentType(), current.terminationDate(), current.terminationReason());
        try {
            repo.updatePersonal(updated, user.username());
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        audit.record("EMPLOYEES", empId, AuditService.Action.UPDATE, personalValues(current),
                personalValues(updated), user.username());
        return detail(user, load(empId), List.of());
    }

    @Transactional
    public EmployeeDetail transfer(CurrentUser user, long empId, TransferRequest req) {
        permissions.require(user, Module.EMPLOYEE, Action.EDIT);
        EmployeeRow current = lockOrThrow(empId);
        if (!current.isActiveEmployee()) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ACTIVE,
                    "Only active employees can be transferred. Current status: " + current.employmentStatus());
        }
        requireDepartment(req.deptId());
        long jobId = req.jobId() == null ? current.jobId() : requireJob(req.jobId()).jobId();
        Long managerId = req.managerEmpId() == null ? current.managerEmpId() : req.managerEmpId();
        validateManager(req.managerEmpId(), empId);
        String location = req.locationId() == null ? current.locationCode() : requireLocation(req.locationId());
        repo.updateAssignment(empId, req.deptId(), jobId, managerId, location, user.username());
        repo.insertHistory(new HistoryEntry(empId, "TRANSFER", req.effectiveDate(), current.deptId(), req.deptId(),
                current.jobId(), jobId, current.managerEmpId(), managerId, null, null, current.locationCode(),
                location, null, req.reason()), user.username());
        EmployeeRow updated = load(empId);
        audit.record("EMPLOYEES", empId, AuditService.Action.UPDATE, assignmentValues(current),
                assignmentValues(updated), user.username());
        notifications.queue(empId, "Transfer confirmed", "You are transferred to " + updated.deptName()
                + " effective " + req.effectiveDate() + ".", "EMPLOYEES", empId, user.username());
        if (managerId != null && !Objects.equals(managerId, current.managerEmpId())) {
            notifications.queue(managerId, "New direct report", updated.fullName() + " joins your team effective "
                    + req.effectiveDate() + ".", "EMPLOYEES", empId, user.username());
        }
        return detail(user, updated, List.of());
    }

    @Transactional
    public EmployeeDetail promote(CurrentUser user, long empId, PromoteRequest req) {
        permissions.require(user, Module.EMPLOYEE, Action.EDIT);
        EmployeeRow current = lockOrThrow(empId);
        if (!current.isActiveEmployee()) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ACTIVE,
                    "Only active employees can be promoted. Current status: " + current.employmentStatus());
        }
        JobRef job = requireJob(req.jobId());
        BigDecimal oldSalary = salaries.current(empId).map(SalaryRecord::baseSalary).orElse(null);
        repo.updateAssignment(empId, current.deptId(), job.jobId(), current.managerEmpId(), current.locationCode(),
                user.username());
        salaries.changeSalary(empId, req.newSalary(), req.effectiveDate(), "PROMOTION", null, user.username());
        String changeType = job.gradeId() < current.gradeLevel() ? "DEMOTION" : "PROMOTION";
        repo.insertHistory(new HistoryEntry(empId, changeType, req.effectiveDate(), null, null, current.jobId(),
                job.jobId(), null, null, oldSalary, req.newSalary(), null, null, null, req.reason()),
                user.username());
        EmployeeRow updated = load(empId);
        audit.record("EMPLOYEES", empId, AuditService.Action.UPDATE, assignmentValues(current),
                assignmentValues(updated), user.username());
        notifications.queue(empId, "Promotion confirmed", "Your new role is " + updated.jobTitle() + " effective "
                + req.effectiveDate() + ".", "EMPLOYEES", empId, user.username());
        List<String> warnings = new ArrayList<>();
        salaryWarning(req.newSalary(), job).ifPresent(warnings::add);
        return detail(user, updated, warnings);
    }

    @Transactional
    public EmployeeDetail terminate(CurrentUser user, long empId, TerminateRequest req) {
        permissions.require(user, Module.EMPLOYEE, Action.EDIT);
        EmployeeRow current = lockOrThrow(empId);
        if ("TERMINATED".equals(current.employmentStatus())) {
            throw new ApiException(ErrorCode.EMPLOYEE_ALREADY_TERMINATED, "Employee is already terminated");
        }
        if (req.terminationDate().isBefore(current.hireDate())) {
            throw new ApiException(ErrorCode.VALIDATION, "Termination date cannot be before the hire date");
        }
        for (LeaveRequest pending : leaveRepository.requestsForEmployee(empId, "PENDING")) {
            leaveService.cancel(user, pending.requestId(), "Employee terminated");
        }
        repo.markTerminated(empId, req.terminationDate(), req.reason().trim(), user.username());
        repo.insertHistory(new HistoryEntry(empId, "TERMINATION", req.terminationDate(), current.deptId(), null,
                current.jobId(), null, current.managerEmpId(), null, null, null, current.locationCode(), null,
                null, req.reason().trim()), user.username());
        EmployeeRow updated = load(empId);
        audit.record("EMPLOYEES", empId, AuditService.Action.UPDATE, statusValues(current), statusValues(updated),
                user.username());
        if (current.managerEmpId() != null) {
            notifications.queue(current.managerEmpId(), "Employee termination", current.fullName()
                    + " leaves effective " + req.terminationDate() + ".", "EMPLOYEES", empId, user.username());
        }
        return detail(user, updated, List.of());
    }

    @Transactional
    public EmployeeDetail rehire(CurrentUser user, long empId, RehireRequest req) {
        permissions.require(user, Module.EMPLOYEE, Action.EDIT);
        EmployeeRow current = lockOrThrow(empId);
        if (!"TERMINATED".equals(current.employmentStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only terminated employees can be rehired. Current status: "
                    + current.employmentStatus());
        }
        validateHireDate(req.hireDate(), LocalDate.now(clock));
        if (current.terminationDate() != null && !req.hireDate().isAfter(current.terminationDate())) {
            throw new ApiException(ErrorCode.VALIDATION, "Rehire date must be after the termination date");
        }
        requireDepartment(req.deptId());
        JobRef job = requireJob(req.jobId());
        validateManager(req.managerEmpId(), empId);
        if (current.email() != null && repo.activeEmailTaken(current.email(), empId)) {
            throw new ApiException(ErrorCode.EMPLOYEE_DUPLICATE_EMAIL, "Email already in use: " + current.email());
        }
        String location = repo.departmentLocation(req.deptId()).orElse(current.locationCode());
        repo.markRehired(empId, req.hireDate(), req.deptId(), req.jobId(), req.managerEmpId(), location,
                user.username());
        List<SalaryRecord> history = salaries.history(empId);
        BigDecimal oldSalary = history.isEmpty() ? null : history.get(0).baseSalary();
        BigDecimal salary = req.baseSalary() != null ? req.baseSalary() : oldSalary;
        List<String> warnings = new ArrayList<>();
        if (salary != null) {
            salaries.changeSalary(empId, salary, req.hireDate(), "REHIRE", null, user.username());
            salaryWarning(salary, job).ifPresent(warnings::add);
        }
        repo.insertHistory(new HistoryEntry(empId, "REHIRE", req.hireDate(), null, req.deptId(), null, req.jobId(),
                null, req.managerEmpId(), oldSalary, salary, null, location, null, null), user.username());
        EmployeeRow updated = load(empId);
        audit.record("EMPLOYEES", empId, AuditService.Action.UPDATE, statusValues(current), statusValues(updated),
                user.username());
        if (req.managerEmpId() != null) {
            notifications.queue(req.managerEmpId(), "New direct report", updated.fullName()
                    + " rejoins your team on " + req.hireDate() + ".", "EMPLOYEES", empId, user.username());
        }
        return detail(user, updated, warnings);
    }

    // ---- dependents & emergency contacts ----

    public List<Dependent> dependents(CurrentUser user, long empId) {
        load(empId);
        requireSensitive(user, empId);
        return repo.dependents(empId);
    }

    @Transactional
    public Dependent addDependent(CurrentUser user, long empId, Dependent d) {
        load(empId);
        requireSelfOrEdit(user, empId);
        Dependent clean = normalize(d);
        long id = repo.insertDependent(empId, clean, user.username());
        audit.record("EMPLOYEE_DEPENDENTS", id, AuditService.Action.INSERT, null, clean, user.username());
        return repo.dependent(empId, id).orElseThrow();
    }

    @Transactional
    public Dependent updateDependent(CurrentUser user, long empId, long dependentId, Dependent d) {
        requireSelfOrEdit(user, empId);
        Dependent old = repo.dependent(empId, dependentId).orElseThrow(() -> notFound("Dependent", dependentId));
        Dependent clean = normalize(d);
        repo.updateDependent(dependentId, clean, user.username());
        audit.record("EMPLOYEE_DEPENDENTS", dependentId, AuditService.Action.UPDATE, old, clean, user.username());
        return repo.dependent(empId, dependentId).orElseThrow();
    }

    @Transactional
    public void deleteDependent(CurrentUser user, long empId, long dependentId) {
        requireSelfOrEdit(user, empId);
        Dependent old = repo.dependent(empId, dependentId).orElseThrow(() -> notFound("Dependent", dependentId));
        repo.deactivateDependent(dependentId, user.username());
        audit.record("EMPLOYEE_DEPENDENTS", dependentId, AuditService.Action.DELETE, old, null, user.username());
    }

    public List<EmergencyContact> emergencyContacts(CurrentUser user, long empId) {
        load(empId);
        requireSensitive(user, empId);
        return repo.emergencyContacts(empId);
    }

    @Transactional
    public EmergencyContact addEmergencyContact(CurrentUser user, long empId, EmergencyContact c) {
        load(empId);
        requireSelfOrEdit(user, empId);
        EmergencyContact clean = normalize(c, repo.emergencyContacts(empId).isEmpty());
        if (Boolean.TRUE.equals(clean.isPrimary())) {
            repo.demotePrimaryContacts(empId, user.username());
        }
        long id = repo.insertContact(empId, clean, user.username());
        audit.record("EMERGENCY_CONTACTS", id, AuditService.Action.INSERT, null, clean, user.username());
        return repo.emergencyContact(empId, id).orElseThrow();
    }

    @Transactional
    public EmergencyContact updateEmergencyContact(CurrentUser user, long empId, long contactId,
                                                   EmergencyContact c) {
        requireSelfOrEdit(user, empId);
        EmergencyContact old = repo.emergencyContact(empId, contactId)
                .orElseThrow(() -> notFound("Emergency contact", contactId));
        EmergencyContact clean = normalize(c, false);
        if (Boolean.TRUE.equals(clean.isPrimary())) {
            repo.demotePrimaryContacts(empId, user.username());
        }
        repo.updateContact(contactId, clean, user.username());
        audit.record("EMERGENCY_CONTACTS", contactId, AuditService.Action.UPDATE, old, clean, user.username());
        return repo.emergencyContact(empId, contactId).orElseThrow();
    }

    @Transactional
    public void deleteEmergencyContact(CurrentUser user, long empId, long contactId) {
        requireSelfOrEdit(user, empId);
        EmergencyContact old = repo.emergencyContact(empId, contactId)
                .orElseThrow(() -> notFound("Emergency contact", contactId));
        repo.deactivateContact(contactId, user.username());
        audit.record("EMERGENCY_CONTACTS", contactId, AuditService.Action.DELETE, old, null, user.username());
    }

    // ---- helpers ----

    private EmployeeRow load(long empId) {
        return repo.find(empId).orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND,
                "Employee not found: " + empId));
    }

    private EmployeeRow lockOrThrow(long empId) {
        return repo.lock(empId).orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND,
                "Employee not found: " + empId));
    }

    private EmployeeDetail detail(CurrentUser user, EmployeeRow row, List<String> warnings) {
        LocalDate end = row.terminationDate() != null ? row.terminationDate() : LocalDate.now(clock);
        BigDecimal tenure = BigDecimal.valueOf(Math.max(0, ChronoUnit.DAYS.between(row.hireDate(), end)))
                .divide(BigDecimal.valueOf(365.25), 1, RoundingMode.HALF_UP);
        SalaryRecord salary = canSeeSensitive(user, row.empId()) ? salaries.current(row.empId()).orElse(null) : null;
        return EmployeeDetail.of(row, tenure, salary, warnings);
    }

    /** Salary, dependents and contacts: the employee, anyone above them, or grade 5+. */
    boolean canSeeSensitive(CurrentUser user, long empId) {
        return user.empId() == empId || user.gradeLevel() >= PermissionService.VIEW_ALL_GRADE
                || repo.managementChain(empId).contains(user.empId());
    }

    private void requireSensitive(CurrentUser user, long empId) {
        if (!canSeeSensitive(user, empId)) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE,
                    "Insufficient privilege to view this employee's personal records");
        }
    }

    private void requireSelfOrEdit(CurrentUser user, long empId) {
        if (user.empId() != empId) {
            permissions.require(user, Module.EMPLOYEE, Action.EDIT);
        }
    }

    private void validateHireDate(LocalDate hireDate, LocalDate today) {
        if (hireDate.isAfter(today.plusDays(MAX_FUTURE_HIRE_DAYS))) {
            throw new ApiException(ErrorCode.EMPLOYEE_HIRE_DATE_TOO_FAR,
                    "Hire date cannot be more than " + MAX_FUTURE_HIRE_DAYS + " days in the future");
        }
    }

    private void requireDepartment(long deptId) {
        if (!repo.departmentActive(deptId).orElse(false)) {
            throw new ApiException(ErrorCode.EMPLOYEE_INVALID_DEPARTMENT, "Invalid or inactive department: " + deptId);
        }
    }

    private JobRef requireJob(long jobId) {
        return repo.job(jobId).filter(JobRef::active)
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_INVALID_JOB, "Invalid or inactive job: " + jobId));
    }

    private String requireLocation(long locationId) {
        return repo.activeLocationCode(locationId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION, "Invalid or inactive location: " + locationId));
    }

    private void validateManager(Long managerEmpId, Long empId) {
        if (managerEmpId == null) {
            return;
        }
        EmployeeSummary manager = lookup.findById(managerEmpId)
                .filter(EmployeeSummary::isActiveEmployee)
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_INVALID_MANAGER,
                        "Manager must be an active employee: " + managerEmpId));
        if (empId != null && (manager.empId() == empId || repo.managementChain(managerEmpId).contains(empId))) {
            throw new ApiException(ErrorCode.EMPLOYEE_INVALID_MANAGER, "Circular reporting relationship");
        }
    }

    private static Optional<String> salaryWarning(BigDecimal salary, JobRef job) {
        return Validators.salaryForGrade(salary, job.minSalary(), job.maxSalary(), job.gradeCode());
    }

    private static String requireName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.EMPLOYEE_NAME_REQUIRED, label + " is required");
        }
        String name = value.trim().toUpperCase();
        if (name.length() > 50) {
            throw new ApiException(ErrorCode.VALIDATION, label + " must be at most 50 characters");
        }
        return name;
    }

    private static String normalizeEmail(String email) {
        String value = email == null ? null : email.trim().toLowerCase();
        if (!Validators.isValidEmail(value)) {
            throw new ApiException(ErrorCode.VALIDATION, "Invalid email address");
        }
        return value;
    }

    private static String normalizeGender(String gender) {
        String g = trimToUpper(gender);
        if (g != null && !GENDERS.contains(g)) {
            throw new ApiException(ErrorCode.VALIDATION, "Gender must be M, F or O");
        }
        return g;
    }

    private static void validatePhone(String phone) {
        if (phone != null && !phone.isBlank() && !Validators.isValidPhone(phone.trim())) {
            throw new ApiException(ErrorCode.VALIDATION, "Invalid phone number: " + phone);
        }
    }

    private static Dependent normalize(Dependent d) {
        String relationship = trimToUpper(d.relationship());
        if (!RELATIONSHIPS.contains(relationship)) {
            throw new ApiException(ErrorCode.VALIDATION, "Invalid relationship: " + d.relationship());
        }
        return new Dependent(null, d.firstName().trim().toUpperCase(), d.lastName().trim().toUpperCase(),
                relationship, d.dateOfBirth(), normalizeGender(d.gender()), Boolean.TRUE.equals(d.benefitsEligible()));
    }

    private static EmergencyContact normalize(EmergencyContact c, boolean firstContact) {
        validatePhone(c.phonePrimary());
        validatePhone(c.phoneSecondary());
        String email = blankToNull(c.email());
        if (email != null) {
            email = normalizeEmail(email);
        }
        boolean primary = c.isPrimary() == null ? firstContact : c.isPrimary();
        return new EmergencyContact(null, c.contactName().trim(), blankToNull(c.relationship()),
                c.phonePrimary().trim(), blankToNull(c.phoneSecondary()), email, primary);
    }

    private static ApiException duplicate(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("uk_emp_number")) {
            return new ApiException(ErrorCode.EMPLOYEE_DUPLICATE_NUMBER, "Duplicate employee number");
        }
        return new ApiException(ErrorCode.EMPLOYEE_DUPLICATE_EMAIL, "Email already in use");
    }

    private static ApiException notFound(String what, long id) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found: " + id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trimToUpper(String value) {
        String v = blankToNull(value);
        return v == null ? null : v.toUpperCase();
    }

    private static String patch(String requested, String current) {
        return requested == null ? current : blankToNull(requested);
    }

    private static Map<String, Object> auditValues(EmployeeRow r) {
        Map<String, Object> m = assignmentValues(r);
        m.putAll(personalValues(r));
        m.putAll(statusValues(r));
        m.put("empNumber", r.empNumber());
        m.put("hireDate", r.hireDate());
        return m;
    }

    private static Map<String, Object> assignmentValues(EmployeeRow r) {
        Map<String, Object> m = new HashMap<>();
        m.put("deptId", r.deptId());
        m.put("jobId", r.jobId());
        m.put("managerEmpId", r.managerEmpId());
        m.put("locationCode", r.locationCode());
        return m;
    }

    private static Map<String, Object> personalValues(EmployeeRow r) {
        Map<String, Object> m = new HashMap<>();
        m.put("firstName", r.firstName());
        m.put("middleName", r.middleName());
        m.put("lastName", r.lastName());
        m.put("email", r.email());
        m.put("phoneWork", r.phoneWork());
        m.put("phoneMobile", r.phoneMobile());
        m.put("addressLine1", r.addressLine1());
        m.put("addressLine2", r.addressLine2());
        m.put("city", r.city());
        m.put("state", r.state());
        m.put("postalCode", r.postalCode());
        m.put("country", r.country());
        return m;
    }

    private static Map<String, Object> statusValues(EmployeeRow r) {
        Map<String, Object> m = new HashMap<>();
        m.put("employmentStatus", r.employmentStatus());
        m.put("active", r.active());
        m.put("terminationDate", r.terminationDate());
        m.put("terminationReason", r.terminationReason());
        m.put("hireDate", r.hireDate());
        m.put("deptId", r.deptId());
        m.put("jobId", r.jobId());
        return m;
    }
}
