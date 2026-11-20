package com.hrms.payroll;

import com.hrms.common.ApiException;
import com.hrms.common.AuditWriter;
import com.hrms.common.ErrorCode;
import com.hrms.employee.SalaryRecordPort;
import com.hrms.payroll.PayrollModels.PayFrequency;
import com.hrms.payroll.PayrollModels.SalaryRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payroll's implementation of the employee module's {@link SalaryRecordPort} (OQ-01, PKG-P01). Runs inside the
 * employee transaction (MANDATORY), so a hire, promotion, rehire or termination and its salary rows commit or
 * roll back together. One audit row per salary-record insert or update (TRG-009, DEF-20 fix).
 */
@Component
public class PayrollSalaryRecords implements SalaryRecordPort {

    static final String TABLE = "SALARY_RECORDS";

    private final JdbcClient jdbc;
    private final AuditWriter audit;
    private final Clock clock;

    public PayrollSalaryRecords(JdbcClient jdbc, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    private record Ended(long salaryId, BigDecimal salary, LocalDate effective) {
    }

    /** create_salary_record: salary > 0 (-20101), end-date the prior active record(s) at effective - 1, insert. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createSalaryRecord(SalaryChange c) {
        if (c.baseSalary() == null || c.baseSalary().signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_SALARY, "Salary must be positive: " + c.baseSalary());
        }
        endActive(c.empId(), c.effectiveDate().minusDays(1), c.actor(),
                "AND effective_date < :effective", c.effectiveDate());
        long id = jdbc.sql("""
                INSERT INTO hrms.salary_records (emp_id, effective_date, base_salary, currency_code, pay_frequency,
                    salary_basis, change_reason, change_pct, active_flag, created_by, created_date)
                VALUES (:empId, :effective, :salary, 'USD', 'MONTHLY', 'ANNUAL', :reason, :pct, true, :actor, :now)
                RETURNING salary_id
                """).param("empId", c.empId()).param("effective", c.effectiveDate()).param("salary", c.baseSalary())
                .param("reason", c.reason().name()).param("pct", c.changePct()).param("actor", c.actor())
                .param("now", now()).query(Long.class).single();
        audit.write(TABLE, id, "INSERT", null, snapshot(c.empId(), c.baseSalary(), c.effectiveDate(), true, null),
                c.actor(), null, null);
    }

    /** The salary effective on {@code asOf} (get_salary_as_of); empty, not 0, when there is none (DEF-P5-2). */
    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> currentBaseSalary(long empId, LocalDate asOf) {
        return salaryAsOf(jdbc, empId, asOf);
    }

    static Optional<BigDecimal> salaryAsOf(JdbcClient jdbc, long empId, LocalDate asOf) {
        return jdbc.sql("""
                SELECT base_salary FROM hrms.salary_records
                WHERE emp_id = :empId AND effective_date <= :asOf AND (end_date IS NULL OR end_date >= :asOf)
                ORDER BY effective_date DESC, salary_id DESC LIMIT 1
                """).param("empId", empId).param("asOf", asOf).query(BigDecimal.class).optional();
    }

    /** Terminate (PKG_EMPLOYEE.terminate_employee L695-711): end the active salary and pay elements. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void endCompensation(long empId, LocalDate endDate, String actor) {
        endActive(empId, endDate, actor, "", null);
        jdbc.sql("""
                UPDATE hrms.employee_pay_elements SET end_date = :end, active_flag = false, modified_by = :actor,
                    modified_date = :now
                WHERE emp_id = :empId AND active_flag
                """).param("end", endDate).param("actor", actor).param("now", now()).param("empId", empId).update();
    }

    private void endActive(long empId, LocalDate endDate, String actor, String extraWhere, LocalDate effective) {
        var stmt = jdbc.sql("""
                UPDATE hrms.salary_records SET end_date = :end, active_flag = false, modified_by = :actor,
                    modified_date = :now
                WHERE emp_id = :empId AND active_flag %s
                RETURNING salary_id, base_salary, effective_date
                """.formatted(extraWhere)).param("end", endDate).param("actor", actor).param("now", now())
                .param("empId", empId);
        if (effective != null) {
            stmt = stmt.param("effective", effective);
        }
        List<Ended> ended = stmt.query((rs, i) -> new Ended(rs.getLong(1), rs.getBigDecimal(2),
                rs.getObject(3, LocalDate.class))).list();
        for (Ended e : ended) {
            audit.write(TABLE, e.salaryId(), "UPDATE", snapshot(empId, e.salary(), e.effective(), true, null),
                    snapshot(empId, e.salary(), e.effective(), false, endDate), actor, null, null);
        }
    }

    /** FRM-039: salary history is read-only outside the salary-record operations. Newest first. */
    @Transactional(readOnly = true)
    public List<SalaryRecord> salaryRecords(long empId) {
        return jdbc.sql("""
                SELECT salary_id, emp_id, effective_date, end_date, base_salary, currency_code, pay_frequency,
                       salary_basis, change_reason, change_pct, active_flag
                FROM hrms.salary_records WHERE emp_id = :empId ORDER BY effective_date DESC, salary_id DESC
                """).param("empId", empId).query((rs, i) -> new SalaryRecord(rs.getLong(1), rs.getLong(2),
                rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getBigDecimal(5),
                rs.getString(6), rs.getString(7) == null ? null : PayFrequency.valueOf(rs.getString(7)),
                rs.getString(8), rs.getString(9), rs.getBigDecimal(10), rs.getBoolean(11))).list();
    }

    /** The TRG_SALARY_AUDIT payload {emp_id, salary, effective, active} (D-08), plus the end date. */
    private static Map<String, Object> snapshot(long empId, BigDecimal salary, LocalDate effective, boolean active,
            LocalDate endDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("empId", empId);
        m.put("salary", salary);
        m.put("effective", effective == null ? null : effective.toString());
        m.put("active", active);
        m.put("endDate", endDate == null ? null : endDate.toString());
        return m;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
