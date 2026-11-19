package com.hrms.payroll;

import static com.hrms.payroll.PayrollModels.*;

import com.hrms.platform.audit.AuditService;
import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.employee.EmployeeSummary;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.param.ParameterService;
import com.hrms.platform.salary.SalaryRecord;
import com.hrms.platform.salary.SalaryRecordService;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PayrollService {
    private static final BigDecimal MAX_SALARY = new BigDecimal("9999999999.99");
    private static final BigDecimal MAX_SALARY_CHANGE_PCT = new BigDecimal("999.99");

    private final PayrollRepository repository;
    private final PayrollCalculation calculation;
    private final EmployeeLookup employees;
    private final SalaryRecordService salaries;
    private final AuditService audit;
    private final ParameterService params;
    private final PermissionService permissions;
    private final Clock clock;

    PayrollService(PayrollRepository repository, PayrollCalculation calculation, EmployeeLookup employees,
                   SalaryRecordService salaries, AuditService audit, ParameterService params,
                   PermissionService permissions, Clock clock) {
        this.repository = repository;
        this.calculation = calculation;
        this.employees = employees;
        this.salaries = salaries;
        this.audit = audit;
        this.params = params;
        this.permissions = permissions;
        this.clock = clock;
    }

    List<PayPeriod> periods(Integer year, PeriodFilter status, CurrentUser user) {
        require(user, Action.VIEW);
        return repository.periods(year, status);
    }

    PayPeriod currentPeriod(CurrentUser user) {
        require(user, Action.VIEW);
        String preferred = params.find("PAYROLL", "DEFAULT_PAY_FREQUENCY").orElse("MONTHLY");
        return repository.currentPeriod(today(), preferred)
                .orElseThrow(() -> notFound("No open pay period for today"));
    }

    @Transactional
    public List<PayPeriod> createPeriods(CreatePeriods request, CurrentUser user) {
        require(user, Action.CREATE);
        int year = request.year();
        if (request.frequency() == PeriodFrequency.BIWEEKLY) {
            return createBiweeklyPeriods(year, user);
        }
        List<PayPeriod> periods = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = start.with(TemporalAdjusters.lastDayOfMonth());
            LocalDate payDate = switch (end.getDayOfWeek()) {
                case SATURDAY -> end.minusDays(1);
                case SUNDAY -> end.minusDays(2);
                default -> end;
            };
            String name = start.format(DateTimeFormatter.ofPattern("uuuu-MM (MMM)", Locale.US));
            periods.add(ensurePeriod(name, request.frequency(), start, end, payDate, user));
        }
        return periods;
    }

    private List<PayPeriod> createBiweeklyPeriods(int year, CurrentUser user) {
        LocalDate firstFriday = LocalDate.of(year, 1, 1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        LocalDate endOfYear = LocalDate.of(year, 12, 31);
        List<PayPeriod> periods = new ArrayList<>();
        for (LocalDate start = firstFriday.minusDays(13); !start.isAfter(endOfYear); start = start.plusDays(14)) {
            LocalDate end = start.plusDays(13);
            String name = String.format(Locale.ROOT, "%04d-BW-%02d", year, periods.size() + 1);
            periods.add(ensurePeriod(name, PeriodFrequency.BIWEEKLY, start, end, end.plusDays(5), user));
        }
        return periods;
    }

    private PayPeriod ensurePeriod(String name, PeriodFrequency frequency, LocalDate start, LocalDate end,
                                   LocalDate payDate, CurrentUser user) {
        return repository.insertPeriod(name, frequency, start, end, payDate, actor(user))
                .map(created -> {
                    audit.record("PAY_PERIODS", created.periodId(), AuditService.Action.INSERT, null, created,
                            user.username());
                    return created;
                }).orElseGet(() -> repository.periodByName(name, frequency));
    }

    @Transactional
    public PayPeriod closePeriod(long periodId, CurrentUser user) {
        require(user, Action.EDIT);
        PayPeriod previous = period(periodId, true);
        ensureOpenForChanges(previous);
        PayPeriod closed = repository.closePeriod(periodId, now(), actor(user));
        audit.record("PAY_PERIODS", periodId, AuditService.Action.UPDATE, previous, closed, user.username());
        return closed;
    }

    List<PayrollRun> runs(Long periodId, CurrentUser user) {
        require(user, Action.VIEW);
        return repository.runs(periodId);
    }

    PayrollRun getRun(long runId, CurrentUser user) {
        require(user, Action.VIEW);
        return run(runId, false);
    }

    @Transactional
    public PayrollRun createRun(CreateRun request, CurrentUser user) {
        require(user, Action.CREATE);
        ensureOpenForChanges(period(request.periodId(), true));
        RunType type = request.runType() == null ? RunType.REGULAR : request.runType();
        PayrollRun created = repository.insertRun(request.periodId(), type, now(), actor(user));
        audit.record("PAYROLL_RUNS", created.runId(), AuditService.Action.INSERT, null, created, user.username());
        return created;
    }

    @Transactional
    public PayrollRun calculateRun(long runId, CurrentUser user) {
        require(user, Action.EDIT);
        PayrollRun previous = run(runId, true);
        ensureRunStatus(previous, "PENDING", "calculate");
        PayPeriod period = period(previous.periodId(), false);
        int year = period.endDate().getYear();
        // A second run for this tax year must see the first run's committed withholding base.
        repository.lockScope("tax-year:" + year);
        String actor = actor(user);
        repository.markCalculating(runId, now(), actor);

        var rules = calculation.loadTaxRules();
        List<EmployeeSummary> activeEmployees = employees.activeEmployees();
        int errors = 0;
        for (EmployeeSummary employee : activeEmployees) {
            long empId = employee.empId();
            List<PayrollCalculation.Line> lines;
            try {
                lines = calculateEmployee(runId, period, rules, empId);
            } catch (ApiException failure) {
                repository.insertEmployeeError(runId, empId, failure.getCode() + ": " + failure.getMessage(), actor);
                errors++;
                continue;
            }
            repository.insertDetails(runId, empId, lines, actor);
        }
        PayrollRun calculated = repository.finishCalculation(runId, activeEmployees.size() - errors, errors, now(), actor);
        return auditRun(previous, calculated, user);
    }

    private List<PayrollCalculation.Line> calculateEmployee(long runId, PayPeriod period,
                                                            PayrollCalculation.TaxRules rules, long empId) {
        LocalDate end = period.endDate();
        SalaryRecord salary = salaries.history(empId).stream()
                .filter(record -> !record.effectiveDate().isAfter(end)
                        && (record.endDate() == null || !record.endDate().isBefore(end)))
                .findFirst()
                .filter(record -> record.baseSalary().signum() > 0)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYROLL_NO_ACTIVE_SALARY, "No salary effective at period end"));
        Frequency frequency = Frequency.valueOf(period.frequency());
        TaxInfo w4 = repository.taxInfo(empId, end.getYear()).orElse(null);
        BigDecimal previousGross = repository.grossBeforeCalculation(empId, runId, end);
        return calculation.calculate(salary, frequency, w4, previousGross,
                repository.payElements(empId, period.startDate(), end, true), rules);
    }

    @Transactional
    public PayrollRun approveRun(long runId, CurrentUser user) {
        require(user, Action.APPROVE);
        PayrollRun previous = run(runId, true);
        ensureRunStatus(previous, "CALCULATED", "approve");
        return auditRun(previous, repository.approveRun(runId, now(), actor(user)), user);
    }

    @Transactional
    public PayrollRun reverseRun(long runId, CurrentUser user) {
        require(user, Action.EDIT);
        PayrollRun previous = run(runId, true);
        if ("REVERSED".equals(previous.status())) {
            return previous;
        }
        repository.lockScope("tax-year:" + period(previous.periodId(), false).endDate().getYear());
        return auditRun(previous, repository.reverseRun(runId, now(), actor(user)), user);
    }

    List<PayrollDetail> details(long runId, Long empId, CurrentUser user) {
        getRun(runId, user);
        return repository.details(runId, empId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Payslip payslip(long runId, long empId, CurrentUser user) {
        requireEmployeeView(empId, user);
        EmployeeSummary employee = employee(empId);
        PayrollRun run = run(runId, false);
        PayPeriod period = period(run.periodId(), false);
        List<PayrollDetail> lines = repository.details(runId, empId).stream()
                .filter(line -> "CALCULATED".equals(line.status())).toList();
        if (lines.isEmpty()) {
            throw notFound("Payslip not found");
        }
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal taxes = BigDecimal.ZERO;
        for (PayrollDetail line : lines) {
            switch (line.elementType()) {
                case EARNING -> gross = gross.add(line.amount());
                case TAX -> {
                    taxes = taxes.subtract(line.amount());
                    deductions = deductions.subtract(line.amount());
                }
                case DEDUCTION, BENEFIT -> deductions = deductions.subtract(line.amount());
            }
        }
        Ytd ytd = repository.ytd(empId, period.endDate(), runId);
        return new Payslip(runId, empId, employee.fullName(), period, lines, gross, deductions, taxes,
                gross.subtract(deductions), ytd.gross(), ytd.taxes(), ytd.net());
    }

    Ytd ytd(long empId, Integer year, CurrentUser user) {
        requireEmployeeView(empId, user);
        employee(empId);
        int selectedYear = year == null ? today().getYear() : year;
        if (selectedYear < 1 || selectedYear > 9999) {
            throw new ApiException(ErrorCode.VALIDATION, "Year must be between 1 and 9999");
        }
        return repository.ytd(empId, LocalDate.of(selectedYear, 12, 31), null);
    }

    @Transactional
    public SalaryRecord changeSalary(long empId, ChangeSalary request, CurrentUser user) {
        require(user, Action.EDIT);
        BigDecimal amount = request.baseSalary();
        if (amount.compareTo(new BigDecimal("0.005")) < 0 || amount.compareTo(MAX_SALARY) > 0) {
            throw new ApiException(ErrorCode.PAYROLL_INVALID_SALARY, "Salary must be positive and fit the supported amount");
        }
        BigDecimal salary = amount.setScale(2, RoundingMode.HALF_UP);
        if (request.effectiveDate().getYear() < 1 || request.effectiveDate().getYear() > 9999) {
            throw new ApiException(ErrorCode.VALIDATION, "Effective date must have a four-digit calendar year");
        }
        employee(empId);
        repository.lockScope("salary:" + empId);
        SalaryRecord previous = salaries.current(empId).orElse(null);
        if (previous != null && request.effectiveDate().isBefore(previous.effectiveDate())) {
            throw new ApiException(ErrorCode.CONFLICT, "Effective date precedes the current salary record");
        }
        if (previous != null && previous.baseSalary().signum() > 0) {
            BigDecimal percent = salary.subtract(previous.baseSalary()).movePointRight(2)
                    .divide(previous.baseSalary(), 2, RoundingMode.HALF_UP);
            if (percent.abs().compareTo(MAX_SALARY_CHANGE_PCT) > 0) {
                throw new ApiException(ErrorCode.PAYROLL_INVALID_SALARY, "Salary change exceeds the supported percentage");
            }
        }
        Frequency frequency = request.payFrequency();
        long salaryId = salaries.changeSalary(empId, salary, request.effectiveDate(), request.changeReason(),
                frequency == null ? null : frequency.name(), actor(user));
        List<SalaryRecord> history = salaries.history(empId);
        SalaryRecord created = history.stream().filter(record -> record.salaryId() == salaryId).findFirst().orElseThrow();
        if (previous != null) {
            SalaryRecord closed = history.stream().filter(record -> record.salaryId() == previous.salaryId())
                    .findFirst().orElseThrow();
            audit.record("SALARY_RECORDS", previous.salaryId(), AuditService.Action.UPDATE, previous, closed, user.username());
        }
        audit.record("SALARY_RECORDS", salaryId, AuditService.Action.INSERT, null, created, user.username());
        return created;
    }

    List<EmployeeElement> payElements(long empId, CurrentUser user) {
        requireEmployeeView(empId, user);
        employee(empId);
        LocalDate today = today();
        return repository.payElements(empId, today, today, false);
    }

    TaxInfo taxInfo(long empId, Integer year, CurrentUser user) {
        requireEmployeeView(empId, user);
        employee(empId);
        return repository.taxInfo(empId, year == null ? today().getYear() : year)
                .orElseThrow(() -> notFound("Employee tax information not found"));
    }

    List<TaxBracket> taxBrackets(int year, FilingStatus status, CurrentUser user) {
        require(user, Action.VIEW);
        return repository.brackets(year, status);
    }

    private PayrollRun auditRun(PayrollRun previous, PayrollRun updated, CurrentUser user) {
        audit.record("PAYROLL_RUNS", updated.runId(), AuditService.Action.UPDATE, previous, updated, user.username());
        return updated;
    }

    private EmployeeSummary employee(long id) {
        return employees.findById(id).orElseThrow(() -> notFound("Employee not found"));
    }

    private PayPeriod period(long id, boolean lock) {
        return repository.period(id, lock).orElseThrow(() -> notFound("Pay period not found"));
    }

    private PayrollRun run(long id, boolean lock) {
        return repository.run(id, lock).orElseThrow(() -> notFound("Payroll run not found"));
    }

    private void requireEmployeeView(long empId, CurrentUser user) {
        if (empId != user.empId()) {
            require(user, Action.VIEW);
        }
    }

    private void require(CurrentUser user, Action action) {
        permissions.require(user, Module.PAYROLL, action);
    }

    private static void ensureOpenForChanges(PayPeriod period) {
        if ("CLOSED".equals(period.status())) {
            throw new ApiException(ErrorCode.PAYROLL_PERIOD_CLOSED, "Pay period is closed");
        }
    }

    private static void ensureRunStatus(PayrollRun run, String expected, String action) {
        if (!expected.equals(run.status())) {
            throw new ApiException(ErrorCode.PAYROLL_WRONG_STATUS, "Cannot " + action + " run in status " + run.status());
        }
    }

    private static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    private static String actor(CurrentUser user) {
        // Legacy payroll tables limit actor columns to 30 characters. Audit retains the full username.
        return user.username().length() <= 30 ? user.username() : "EMP#" + user.empId();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
