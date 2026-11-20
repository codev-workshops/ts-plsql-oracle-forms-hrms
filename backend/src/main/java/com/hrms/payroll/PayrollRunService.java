package com.hrms.payroll;

import com.hrms.common.ApiException;
import com.hrms.common.AuditWriter;
import com.hrms.common.ErrorCode;
import com.hrms.employee.EmployeeReadService;
import com.hrms.payroll.PayrollModels.PayFrequency;
import com.hrms.payroll.PayrollModels.PayrollRun;
import com.hrms.payroll.PayrollModels.PayrollRunError;
import com.hrms.payroll.PayrollModels.PayrollRunStatus;
import com.hrms.payroll.PayrollModels.PayrollRunType;
import com.hrms.payroll.PayrollModels.Payslip;
import com.hrms.payroll.TaxCalculator.TaxTables;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payroll runs: PKG_PAYROLL create_payroll_run, calculate_payroll / calculate_employee_pay, approve_payroll,
 * reverse_payroll, get_payslip (PKG-P03, PKG-P04). The HRMS_PAYROLL button gates are enforced here
 * (FRM-062 PENDING-only calculate) and in the controller's authorities (FRM-063, FRM-064).
 */
@Service
public class PayrollRunService {

    static final String TABLE = "PAYROLL_RUNS";
    static final long BASE_PAY = 1;
    static final long FED_TAX = 100;
    static final long STATE_TAX = 101;
    static final long SOCIAL_SECURITY = 102;
    static final long MEDICARE = 103;
    /** Run statuses whose earnings count towards year-to-date (DEF-06 fix). */
    static final Set<PayrollRunStatus> YTD_RUN_STATUSES = EnumSet.of(PayrollRunStatus.CALCULATED,
            PayrollRunStatus.APPROVED, PayrollRunStatus.PAID);
    /** DEF-21 fix: reverse only a run that has results (legacy reversed any status). */
    static final Set<PayrollRunStatus> REVERSIBLE = EnumSet.of(PayrollRunStatus.CALCULATED,
            PayrollRunStatus.APPROVED, PayrollRunStatus.PAID, PayrollRunStatus.ERROR);

    private static final String SELECT = """
            SELECT r.run_id, r.period_id, p.period_name, r.run_type, r.run_date, r.status, r.total_gross,
                   r.total_deductions, r.total_net, r.employee_count, coalesce(r.error_count, 0), r.submitted_by,
                   r.submitted_date, r.approved_by, r.approved_date
            FROM hrms.payroll_runs r JOIN hrms.pay_periods p ON p.period_id = r.period_id
            """;
    private static final String YTD_SUM = """
            SELECT coalesce(sum(%s), 0)
            FROM hrms.payroll_details y
            JOIN hrms.payroll_runs yr ON yr.run_id = y.run_id
            JOIN hrms.pay_periods yp ON yp.period_id = yr.period_id
            WHERE y.emp_id = %s AND y.status = 'CALCULATED' AND yr.status IN ('CALCULATED', 'APPROVED', 'PAID')
              AND EXTRACT(YEAR FROM yp.pay_date) = EXTRACT(YEAR FROM %s) AND yp.pay_date <= %s
            """;

    private final JdbcClient jdbc;
    private final EmployeeReadService employees;
    private final TaxCalculator taxes;
    private final AuditWriter audit;
    private final Clock clock;

    public PayrollRunService(JdbcClient jdbc, EmployeeReadService employees, TaxCalculator taxes, AuditWriter audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.taxes = taxes;
        this.audit = audit;
        this.clock = clock;
    }

    private record Period(long periodId, LocalDate start, LocalDate end, LocalDate payDate, PayFrequency frequency,
            String status) {
    }

    private record TaxInfo(String filingStatus, int federalAllowances, String stateCode,
            BigDecimal additionalFederal) {
        static final TaxInfo DEFAULT = new TaxInfo("SINGLE", 0, null, BigDecimal.ZERO);
    }

    private record Deduction(long elementId, String elementType, BigDecimal amount, BigDecimal percentage,
            BigDecimal overrideAmount, String calculationType, BigDecimal defaultAmount,
            BigDecimal defaultPercentage) {
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<PayrollRun> list(Long periodId, PayrollRunStatus status) {
        return jdbc.sql(SELECT + """
                WHERE (CAST(:period AS bigint) IS NULL OR r.period_id = :period)
                  AND (CAST(:status AS varchar) IS NULL OR r.status = :status)
                ORDER BY r.run_date DESC, r.run_id DESC
                """).param("period", periodId).param("status", status == null ? null : status.name())
                .query(PayrollRunService::run).list();
    }

    @Transactional(readOnly = true)
    public PayrollRun get(long runId) {
        return jdbc.sql(SELECT + " WHERE r.run_id = :id").param("id", runId).query(PayrollRunService::run)
                .optional().orElseThrow(() -> notFound(runId));
    }

    /** get_payslip, with the YTD placeholders filled (DEF-06 fix): pay-date year, runs CALCULATED/APPROVED/PAID. */
    @Transactional(readOnly = true)
    public List<Payslip> payslips(long runId, Long empId) {
        get(runId);
        return jdbc.sql("""
                SELECT pd.emp_id, e.emp_number, e.first_name || ' ' || e.last_name, pp.period_name, pp.pay_date,
                       sum(CASE WHEN pd.element_type = 'EARNING' THEN pd.amount ELSE 0 END),
                       sum(CASE WHEN pd.element_type IN ('DEDUCTION', 'TAX', 'BENEFIT') THEN abs(pd.amount) ELSE 0 END),
                       sum(pd.amount),
                       sum(CASE WHEN pd.element_id = 100 THEN abs(pd.amount) ELSE 0 END),
                       sum(CASE WHEN pd.element_id = 101 THEN abs(pd.amount) ELSE 0 END),
                       sum(CASE WHEN pd.element_id = 102 THEN abs(pd.amount) ELSE 0 END),
                       sum(CASE WHEN pd.element_id = 103 THEN abs(pd.amount) ELSE 0 END),
                       (%s), (%s)
                FROM hrms.payroll_details pd
                JOIN hrms.employees e ON e.emp_id = pd.emp_id
                JOIN hrms.payroll_runs pr ON pr.run_id = pd.run_id
                JOIN hrms.pay_periods pp ON pp.period_id = pr.period_id
                WHERE pd.run_id = :run AND pd.status <> 'ERROR' AND pd.element_type <> 'ERROR'
                  AND (CAST(:emp AS bigint) IS NULL OR pd.emp_id = :emp)
                GROUP BY pd.emp_id, e.emp_number, e.first_name, e.last_name, pp.period_name, pp.pay_date
                ORDER BY e.last_name, pd.emp_id
                """.formatted(YTD_SUM.formatted("CASE WHEN y.element_type = 'EARNING' THEN y.amount ELSE 0 END",
                        "pd.emp_id", "pp.pay_date", "pp.pay_date"),
                YTD_SUM.formatted("y.amount", "pd.emp_id", "pp.pay_date", "pp.pay_date")))
                .param("run", runId).param("emp", empId)
                .query((rs, i) -> new Payslip(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11),
                        rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBigDecimal(14)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<PayrollRunError> errors(long runId) {
        get(runId);
        return jdbc.sql("""
                SELECT detail_id, emp_id, error_message FROM hrms.payroll_details
                WHERE run_id = :run AND element_type = 'ERROR' ORDER BY emp_id, detail_id
                """).param("run", runId).query((rs, i) -> new PayrollRunError(rs.getLong(1), rs.getLong(2),
                rs.getString(3))).list();
    }

    @Transactional(readOnly = true)
    public List<Long> pendingRunIds() {
        return jdbc.sql("SELECT run_id FROM hrms.payroll_runs WHERE status = 'PENDING' ORDER BY run_id")
                .query(Long.class).list();
    }

    // ---------------------------------------------------------------- commands

    /** create_payroll_run: the period must exist and not be CLOSED (-20102); the run starts PENDING. */
    @Transactional
    public PayrollRun create(PayrollCaller caller, long periodId, PayrollRunType runType) {
        String periodStatus = jdbc.sql("SELECT status FROM hrms.pay_periods WHERE period_id = :id FOR SHARE")
                .param("id", periodId).query(String.class).optional()
                .orElseThrow(() -> PayPeriodService.notFound(periodId));
        if ("CLOSED".equals(periodStatus)) {
            throw new ApiException(ErrorCode.PERIOD_CLOSED, "Cannot create run for closed period: " + periodId);
        }
        PayrollRunType type = runType == null ? PayrollRunType.REGULAR : runType;
        OffsetDateTime now = now();
        long runId = jdbc.sql("""
                INSERT INTO hrms.payroll_runs (period_id, run_type, run_date, status, error_count, submitted_by,
                    submitted_date, created_by, created_date)
                VALUES (:period, :type, :now, 'PENDING', 0, :actor, :now, :actor, :now)
                RETURNING run_id
                """).param("period", periodId).param("type", type.name()).param("now", now)
                .param("actor", caller.actor()).query(Long.class).single();
        audit.write(TABLE, runId, "INSERT", null, Map.of("periodId", periodId, "runType", type.name(), "status",
                "PENDING"), caller.actor(), caller.ipAddress(), caller.sessionId());
        return get(runId);
    }

    /**
     * calculate_payroll. FRM-062: only a PENDING run (legacy checked this in the form only). DEF-16 fix: the
     * whole run is one transaction, so CALCULATING is never visible and a failure leaves the run PENDING. A
     * missing salary is recorded per employee (-20104) and makes the run ERROR, as legacy.
     */
    @Transactional
    public PayrollRun calculate(PayrollCaller caller, long runId) {
        String status = jdbc.sql("SELECT status FROM hrms.payroll_runs WHERE run_id = :id FOR UPDATE NOWAIT")
                .param("id", runId).query(String.class).optional().orElseThrow(() -> notFound(runId));
        if (!PayrollRunStatus.PENDING.name().equals(status)) {
            throw new ApiException(ErrorCode.INVALID_RUN_STATUS,
                    "Can only calculate runs in PENDING status. Status: " + status, null);
        }
        Period period = jdbc.sql("""
                SELECT p.period_id, p.period_start_date, p.period_end_date, p.pay_date, p.pay_frequency, p.status
                FROM hrms.pay_periods p JOIN hrms.payroll_runs r ON r.period_id = p.period_id WHERE r.run_id = :id
                """).param("id", runId).query((rs, i) -> new Period(rs.getLong(1), rs.getObject(2, LocalDate.class),
                rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                PayFrequency.valueOf(rs.getString(5)), rs.getString(6))).single();
        int taxYear = period.payDate().getYear();
        TaxTables tables = taxes.tablesFor(taxYear);
        OffsetDateTime now = now();
        int ok = 0;
        int failed = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        for (long empId : employees.activeEmployeeIds()) {
            Optional<BigDecimal[]> pay = calculateEmployee(caller, runId, empId, period, taxYear, tables, now);
            if (pay.isPresent()) {
                ok++;
                gross = gross.add(pay.get()[0]);
                deductions = deductions.add(pay.get()[1]);
            } else {
                failed++;
            }
        }
        PayrollRunStatus result = failed > 0 ? PayrollRunStatus.ERROR : PayrollRunStatus.CALCULATED;
        jdbc.sql("""
                UPDATE hrms.payroll_runs SET status = :status, employee_count = :ok, error_count = :failed,
                    total_gross = :gross, total_deductions = :ded, total_net = :net, modified_by = :actor,
                    modified_date = :now
                WHERE run_id = :id
                """).param("status", result.name()).param("ok", ok).param("failed", failed).param("gross", gross)
                .param("ded", deductions).param("net", gross.subtract(deductions)).param("actor", caller.actor())
                .param("now", now).param("id", runId).update();
        audit.write(TABLE, runId, "STATUS_CHANGE", Map.of("status", status),
                Map.of("status", result.name(), "employeeCount", ok, "errorCount", failed), caller.actor(),
                caller.ipAddress(), caller.sessionId());
        return get(runId);
    }

    /**
     * calculate_employee_pay. Returns {gross, total deductions}, or empty after writing an ERROR detail when the
     * employee has no salary on the period end. Taxable income is the gross (legacy did not subtract pretax
     * deductions).
     */
    private Optional<BigDecimal[]> calculateEmployee(PayrollCaller caller, long runId, long empId, Period period,
            int taxYear, TaxTables tables, OffsetDateTime now) {
        Optional<BigDecimal> annual = PayrollSalaryRecords.salaryAsOf(jdbc, empId, period.end())
                .filter(s -> s.signum() != 0);
        if (annual.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO hrms.payroll_details (run_id, emp_id, element_id, element_type, amount, status,
                        error_message, created_by, created_date)
                    VALUES (:run, :emp, NULL, 'ERROR', 0, 'ERROR', :msg, :actor, :now)
                    """).param("run", runId).param("emp", empId)
                    .param("msg", ErrorCode.NO_ACTIVE_SALARY.name() + " (-20104): No active salary record for employee "
                            + empId).param("actor", caller.actor()).param("now", now).update();
            return Optional.empty();
        }
        int periods = period.frequency().periodsPerYear();
        BigDecimal gross = annual.get().divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP);
        detail(runId, empId, BASE_PAY, "EARNING", gross, caller, now);

        BigDecimal ytd = priorYtdGross(runId, empId, period.payDate());
        TaxInfo info = jdbc.sql("""
                SELECT filing_status, coalesce(federal_allowances, 0), state_code, coalesce(additional_fed_wh, 0)
                FROM hrms.employee_tax_info WHERE emp_id = :emp AND tax_year = :year AND active_flag
                """).param("emp", empId).param("year", taxYear).query((rs, i) -> new TaxInfo(rs.getString(1),
                rs.getInt(2), rs.getString(3), rs.getBigDecimal(4))).optional().orElse(TaxInfo.DEFAULT);

        BigDecimal total = BigDecimal.ZERO;
        total = total.add(tax(runId, empId, FED_TAX, TaxCalculator.federalTax(tables, gross, info.filingStatus(),
                info.federalAllowances(), info.additionalFederal(), periods), caller, now));
        if (info.stateCode() != null) {
            total = total.add(tax(runId, empId, STATE_TAX, TaxCalculator.stateTax(tables, gross, info.stateCode()),
                    caller, now));
        }
        total = total.add(tax(runId, empId, SOCIAL_SECURITY, TaxCalculator.socialSecurity(tables, gross, ytd),
                caller, now));
        total = total.add(tax(runId, empId, MEDICARE, TaxCalculator.medicare(tables, gross, ytd), caller, now));

        List<Deduction> elements = jdbc.sql("""
                SELECT epe.element_id, pe.element_type, epe.amount, epe.percentage, epe.override_amount,
                       pe.calculation_type, pe.default_amount, pe.default_percentage
                FROM hrms.employee_pay_elements epe JOIN hrms.pay_elements pe ON pe.element_id = epe.element_id
                WHERE epe.emp_id = :emp AND epe.active_flag AND pe.element_type IN ('DEDUCTION', 'BENEFIT')
                  AND epe.effective_date <= :end AND (epe.end_date IS NULL OR epe.end_date >= :start)
                ORDER BY pe.priority_order, epe.element_id
                """).param("emp", empId).param("start", period.start()).param("end", period.end())
                .query((rs, i) -> new Deduction(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6), rs.getBigDecimal(7),
                        rs.getBigDecimal(8))).list();
        for (Deduction d : elements) {
            BigDecimal amount = deductionAmount(d, gross);
            if (amount != null && amount.signum() > 0) {
                detail(runId, empId, d.elementId(), d.elementType(), amount.negate(), caller, now);
                total = total.add(amount);
            }
        }
        return Optional.of(new BigDecimal[] {gross, total});
    }

    /** Override, else FLAT amount (or the element default), else PERCENTAGE of gross, else the amount. */
    static BigDecimal deductionAmount(Deduction d, BigDecimal gross) {
        if (d.overrideAmount() != null) {
            return d.overrideAmount();
        }
        if ("FLAT".equals(d.calculationType())) {
            return d.amount() != null ? d.amount() : d.defaultAmount();
        }
        if ("PERCENTAGE".equals(d.calculationType())) {
            BigDecimal pct = d.percentage() != null ? d.percentage() : d.defaultPercentage();
            return pct == null ? null : gross.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        return d.amount() != null ? d.amount() : BigDecimal.ZERO;
    }

    /**
     * get_ytd_earnings (DEF-06 fix): earnings paid in the same calendar year by pay date, up to this pay date,
     * from runs that are CALCULATED, APPROVED or PAID, excluding the run being calculated (legacy keyed on the
     * period-start year and counted the current run's own gross).
     */
    private BigDecimal priorYtdGross(long runId, long empId, LocalDate payDate) {
        return jdbc.sql(YTD_SUM.formatted("CASE WHEN y.element_type = 'EARNING' THEN y.amount ELSE 0 END",
                        ":emp", "CAST(:pay AS date)", "CAST(:pay AS date)") + " AND y.run_id <> :run")
                .param("emp", empId).param("pay", payDate).param("run", runId).query(BigDecimal.class).single();
    }

    private BigDecimal tax(long runId, long empId, long elementId, BigDecimal amount, PayrollCaller caller,
            OffsetDateTime now) {
        if (amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        detail(runId, empId, elementId, "TAX", amount.negate(), caller, now);
        return amount;
    }

    private void detail(long runId, long empId, long elementId, String type, BigDecimal amount, PayrollCaller caller,
            OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO hrms.payroll_details (run_id, emp_id, element_id, element_type, amount, status,
                    created_by, created_date)
                VALUES (:run, :emp, :element, :type, :amount, 'CALCULATED', :actor, :now)
                """).param("run", runId).param("emp", empId).param("element", elementId).param("type", type)
                .param("amount", amount).param("actor", caller.actor()).param("now", now).update();
    }

    /** approve_payroll: only CALCULATED (-20103). The authority check (FRM-063) is on the route. */
    @Transactional
    public PayrollRun approve(PayrollCaller caller, long runId) {
        String status = lockStatus(runId);
        if (!PayrollRunStatus.CALCULATED.name().equals(status)) {
            throw new ApiException(ErrorCode.INVALID_RUN_STATUS, "Cannot approve run in status: " + status);
        }
        OffsetDateTime now = now();
        jdbc.sql("""
                UPDATE hrms.payroll_runs SET status = 'APPROVED', approved_by = :actor, approved_date = :now,
                    modified_by = :actor, modified_date = :now
                WHERE run_id = :id
                """).param("actor", caller.actor()).param("now", now).param("id", runId).update();
        audit.write(TABLE, runId, "STATUS_CHANGE", Map.of("status", status), Map.of("status", "APPROVED"),
                caller.actor(), caller.ipAddress(), caller.sessionId());
        return get(runId);
    }

    /** reverse_payroll. DEF-21 fix: only from CALCULATED, APPROVED, PAID or ERROR. The reason is audited. */
    @Transactional
    public PayrollRun reverse(PayrollCaller caller, long runId, String reason) {
        String status = lockStatus(runId);
        if (!REVERSIBLE.contains(PayrollRunStatus.valueOf(status))) {
            throw new ApiException(ErrorCode.INVALID_RUN_STATUS, "Cannot reverse run in status: " + status, null);
        }
        OffsetDateTime now = now();
        jdbc.sql("UPDATE hrms.payroll_runs SET status = 'REVERSED', modified_by = :actor, modified_date = :now "
                + "WHERE run_id = :id").param("actor", caller.actor()).param("now", now).param("id", runId).update();
        jdbc.sql("UPDATE hrms.payroll_details SET status = 'REVERSED' WHERE run_id = :id").param("id", runId)
                .update();
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("status", "REVERSED");
        next.put("reason", reason.trim());
        audit.write(TABLE, runId, "STATUS_CHANGE", Map.of("status", status), next, caller.actor(),
                caller.ipAddress(), caller.sessionId());
        return get(runId);
    }

    private String lockStatus(long runId) {
        return jdbc.sql("SELECT status FROM hrms.payroll_runs WHERE run_id = :id FOR UPDATE").param("id", runId)
                .query(String.class).optional().orElseThrow(() -> notFound(runId));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException notFound(long runId) {
        return new ApiException(ErrorCode.NOT_FOUND, "Payroll run not found: " + runId);
    }

    private static PayrollRun run(ResultSet rs, int i) throws SQLException {
        return new PayrollRun(rs.getLong(1), rs.getLong(2), rs.getString(3), PayrollRunType.valueOf(rs.getString(4)),
                rs.getObject(5, OffsetDateTime.class), PayrollRunStatus.valueOf(rs.getString(6)), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getObject(10, Integer.class), rs.getInt(11),
                rs.getString(12), rs.getObject(13, OffsetDateTime.class), rs.getString(14),
                rs.getObject(15, OffsetDateTime.class));
    }
}
