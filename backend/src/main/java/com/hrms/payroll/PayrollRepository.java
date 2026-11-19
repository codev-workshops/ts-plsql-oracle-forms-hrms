package com.hrms.payroll;

import static com.hrms.payroll.PayrollModels.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class PayrollRepository {
    private static final String RUN_FIELDS = """
            run_id, period_id, run_type, status, employee_count,
            coalesce(total_gross, 0) AS total_gross, coalesce(total_deductions, 0) AS total_deductions,
            coalesce(total_net, 0) AS total_net, calculated_at, approved_by
            """;
    private static final String EMPLOYEE_ELEMENTS = """
            SELECT epe.*, pe.element_code, pe.element_name, pe.element_type, pe.calculation_type,
                   pe.default_amount, pe.default_percentage, pe.pretax_flag, pe.priority_order
            FROM employee_pay_elements epe JOIN pay_elements pe USING (element_id)
            WHERE epe.emp_id = ? AND epe.active_flag = 'Y' AND pe.active_flag = 'Y'
              AND epe.effective_date <= ? AND (epe.end_date IS NULL OR epe.end_date >= ?)
            """;
    private static final String TOTALS = """
            coalesce(sum(amount) FILTER (WHERE element_type = 'EARNING'), 0) AS gross,
            coalesce(-sum(amount) FILTER (WHERE element_type IN ('DEDUCTION','BENEFIT','TAX')), 0) AS deductions,
            coalesce(-sum(amount) FILTER (WHERE element_type = 'TAX'), 0) AS taxes,
            coalesce(sum(amount), 0) AS net
            """;
    private static final String YTD_SOURCE = """
            FROM payroll_details d JOIN payroll_runs r USING (run_id) JOIN pay_periods p USING (period_id)
            WHERE d.status = 'CALCULATED'
              AND p.period_end_date >= ? AND p.period_end_date <= ?
            """;

    private final JdbcTemplate jdbc;

    PayrollRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<PayPeriod> periods(Integer year, PeriodFilter status) {
        return jdbc.query("""
                SELECT * FROM pay_periods
                WHERE (?::int IS NULL OR extract(year FROM period_start_date) = ?
                                     OR extract(year FROM period_end_date) = ?)
                  AND (?::text IS NULL OR status = ?)
                ORDER BY period_start_date DESC, period_id DESC
                """, PayrollRepository::mapPeriod, year, year, year, name(status), name(status));
    }

    Optional<PayPeriod> currentPeriod(LocalDate date, String preferredFrequency) {
        return first("""
                SELECT * FROM pay_periods
                WHERE status = 'OPEN' AND ? BETWEEN period_start_date AND period_end_date
                ORDER BY (pay_frequency = ?) DESC, period_start_date DESC, period_id DESC LIMIT 1
                """, PayrollRepository::mapPeriod, date, preferredFrequency);
    }

    Optional<PayPeriod> period(long id, boolean lock) {
        return first("SELECT * FROM pay_periods WHERE period_id = ?" + (lock ? " FOR UPDATE" : ""),
                PayrollRepository::mapPeriod, id);
    }

    Optional<PayPeriod> insertPeriod(String name, PeriodFrequency frequency, LocalDate start, LocalDate end,
                                     LocalDate payDate, String actor) {
        return first("""
                INSERT INTO pay_periods
                    (period_name, pay_frequency, period_start_date, period_end_date, pay_date, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (pay_frequency, period_name) DO NOTHING
                RETURNING *
                """, PayrollRepository::mapPeriod, name, frequency.name(), start, end, payDate, actor);
    }

    PayPeriod periodByName(String name, PeriodFrequency frequency) {
        return first("SELECT * FROM pay_periods WHERE period_name = ? AND pay_frequency = ?",
                PayrollRepository::mapPeriod, name, frequency.name()).orElseThrow();
    }

    PayPeriod closePeriod(long id, LocalDateTime time, String actor) {
        return first("""
                UPDATE pay_periods SET status = 'CLOSED', closed_by = ?, closed_date = ?,
                    modified_by = ?, modified_date = ?
                WHERE period_id = ? RETURNING *
                """, PayrollRepository::mapPeriod, actor, time, actor, time, id).orElseThrow();
    }

    List<PayrollRun> runs(Long periodId) {
        return jdbc.query("SELECT " + RUN_FIELDS + """
                FROM payroll_runs WHERE (?::bigint IS NULL OR period_id = ?) ORDER BY run_id DESC
                """, PayrollRepository::mapRun, periodId, periodId);
    }

    Optional<PayrollRun> run(long id, boolean lock) {
        return first("SELECT " + RUN_FIELDS + "FROM payroll_runs WHERE run_id = ?" + (lock ? " FOR UPDATE" : ""),
                PayrollRepository::mapRun, id);
    }

    PayrollRun insertRun(long periodId, RunType runType, LocalDateTime time, String actor) {
        return first("""
                INSERT INTO payroll_runs (period_id, run_type, run_date, submitted_by, submitted_date, created_by)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING
                """ + RUN_FIELDS, PayrollRepository::mapRun, periodId, runType.name(), time, actor, time, actor)
                .orElseThrow();
    }

    void lockScope(String scope) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, row) -> true, "hrms.payroll:" + scope);
    }

    void markCalculating(long id, LocalDateTime time, String actor) {
        jdbc.update("""
                UPDATE payroll_runs SET status = 'CALCULATING', modified_date = ?, modified_by = ?
                WHERE run_id = ?
                """, time, actor, id);
    }

    PayrollRun finishCalculation(long id, int employees, int errors, LocalDateTime time, String actor) {
        return first("""
                UPDATE payroll_runs SET status = ?, employee_count = ?, error_count = ?, total_gross = a.gross,
                    total_deductions = a.deductions, total_net = a.net, calculated_at = ?,
                    modified_date = ?, modified_by = ?
                FROM (SELECT
                """ + TOTALS + """
                FROM payroll_details WHERE run_id = ? AND status = 'CALCULATED') a
                WHERE run_id = ? RETURNING
                """ + RUN_FIELDS, PayrollRepository::mapRun,
                errors == 0 ? "CALCULATED" : "ERROR", employees, errors, time, time, actor, id, id).orElseThrow();
    }

    PayrollRun approveRun(long id, LocalDateTime time, String actor) {
        return first("""
                UPDATE payroll_runs SET status = 'APPROVED', approved_by = ?, approved_date = ?,
                    modified_date = ?, modified_by = ?
                WHERE run_id = ? RETURNING
                """ + RUN_FIELDS, PayrollRepository::mapRun, actor, time, time, actor, id).orElseThrow();
    }

    PayrollRun reverseRun(long id, LocalDateTime time, String actor) {
        jdbc.update("UPDATE payroll_details SET status = 'REVERSED' WHERE run_id = ?", id);
        return first("""
                UPDATE payroll_runs SET status = 'REVERSED', modified_date = ?, modified_by = ?
                WHERE run_id = ? RETURNING
                """ + RUN_FIELDS, PayrollRepository::mapRun, time, actor, id).orElseThrow();
    }

    List<PayrollDetail> details(long runId, Long empId) {
        return jdbc.query("""
                SELECT d.*, e.element_code
                FROM payroll_details d LEFT JOIN pay_elements e USING (element_id)
                WHERE run_id = ? AND (?::bigint IS NULL OR emp_id = ?) ORDER BY emp_id, detail_id
                """, (rs, row) -> new PayrollDetail(rs.getLong("detail_id"), rs.getLong("emp_id"),
                rs.getObject("element_id", Long.class), rs.getString("element_code"),
                ElementType.valueOf(rs.getString("element_type")), rs.getBigDecimal("amount"),
                rs.getString("status"), rs.getString("error_message")), runId, empId, empId);
    }

    void insertDetails(long runId, long empId, List<PayrollCalculation.Line> lines, String actor) {
        jdbc.batchUpdate("""
                INSERT INTO payroll_details (run_id, emp_id, element_id, element_type, amount, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, lines, lines.size(), (ps, line) -> {
            ps.setLong(1, runId);
            ps.setLong(2, empId);
            ps.setLong(3, line.elementId());
            ps.setString(4, line.type().name());
            ps.setBigDecimal(5, line.amount());
            ps.setString(6, actor);
        });
    }

    void insertEmployeeError(long runId, long empId, String message, String actor) {
        jdbc.update("""
                INSERT INTO payroll_details (run_id, emp_id, element_type, amount, status, error_message, created_by)
                VALUES (?, ?, 'EARNING', 0, 'ERROR', ?, ?)
                """, runId, empId, message, actor);
    }

    BigDecimal grossBeforeCalculation(long empId, long runId, LocalDate periodEnd) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) " + YTD_SOURCE + """
                AND r.status IN ('CALCULATED', 'APPROVED', 'PAID')
                AND d.emp_id = ? AND r.run_id <> ? AND d.element_type = 'EARNING'
                """, BigDecimal.class, periodEnd.withDayOfYear(1), periodEnd, empId, runId);
    }

    Ytd ytd(long empId, LocalDate through, Long throughRunId) {
        return jdbc.queryForObject("SELECT " + TOTALS + YTD_SOURCE + """
                AND (r.status IN ('CALCULATED', 'APPROVED', 'PAID') OR r.run_id = ?)
                AND d.emp_id = ? AND (?::bigint IS NULL OR p.period_end_date < ? OR r.run_id <= ?)
                """, (rs, row) -> new Ytd(through.getYear(), rs.getBigDecimal("gross"), rs.getBigDecimal("deductions"),
                rs.getBigDecimal("taxes"), rs.getBigDecimal("net")), through.withDayOfYear(1), through,
                throughRunId, empId, throughRunId, through, throughRunId);
    }

    List<EmployeeElement> payElements(long empId, LocalDate start, LocalDate end, boolean deductionsOnly) {
        return jdbc.query(EMPLOYEE_ELEMENTS + """
                AND (NOT ? OR pe.element_type IN ('DEDUCTION', 'BENEFIT'))
                ORDER BY pe.priority_order, epe.emp_element_id
                """, (rs, row) -> new EmployeeElement(rs.getLong("emp_element_id"), rs.getLong("emp_id"),
                rs.getLong("element_id"), rs.getString("element_code"), rs.getString("element_name"),
                rs.getString("element_type"), rs.getString("calculation_type"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("percentage"), rs.getBigDecimal("override_amount"), rs.getBigDecimal("default_amount"),
                rs.getBigDecimal("default_percentage"), "Y".equals(rs.getString("pretax_flag")),
                rs.getInt("priority_order"), date(rs, "effective_date"), date(rs, "end_date")),
                empId, end, start, deductionsOnly);
    }

    Optional<TaxInfo> taxInfo(long empId, int year) {
        return first("""
                SELECT * FROM employee_tax_info WHERE emp_id = ? AND tax_year = ? AND active_flag = 'Y'
                """, (rs, row) -> new TaxInfo(rs.getLong("tax_info_id"), rs.getLong("emp_id"), rs.getInt("tax_year"),
                filingStatus(rs),
                rs.getInt("federal_allowances"), rs.getInt("state_allowances"), rs.getBigDecimal("additional_fed_wh"),
                rs.getBigDecimal("additional_state_wh"), "Y".equals(rs.getString("exempt_flag")),
                rs.getString("state_code"), date(rs, "w4_received_date")), empId, year);
    }

    List<TaxBracket> brackets(int year, FilingStatus filingStatus) {
        return jdbc.query("""
                SELECT * FROM tax_brackets WHERE tax_year = ? AND active_flag = 'Y'
                  AND (?::text IS NULL OR filing_status = ?)
                ORDER BY filing_status, state_code NULLS FIRST, bracket_min, bracket_id
                """, (rs, row) -> new TaxBracket(rs.getLong("bracket_id"), rs.getInt("tax_year"), filingStatus(rs),
                rs.getBigDecimal("bracket_min"), rs.getBigDecimal("bracket_max"), rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("base_tax"), rs.getString("state_code")), year, name(filingStatus), name(filingStatus));
    }

    private static FilingStatus filingStatus(ResultSet rs) throws SQLException {
        try {
            return FilingStatus.valueOf(rs.getString("filing_status"));
        } catch (IllegalArgumentException ex) {
            // Legacy employee W-4 rows are not protected by a filing-status check constraint.
            return FilingStatus.SINGLE;
        }
    }

    private <T> Optional<T> first(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static LocalDate date(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, LocalDate.class);
    }

    private static PayPeriod mapPeriod(ResultSet rs, int row) throws SQLException {
        return new PayPeriod(rs.getLong("period_id"), rs.getString("period_name"), rs.getString("pay_frequency"),
                date(rs, "period_start_date"), date(rs, "period_end_date"), date(rs, "pay_date"), rs.getString("status"));
    }

    private static PayrollRun mapRun(ResultSet rs, int row) throws SQLException {
        return new PayrollRun(rs.getLong("run_id"), rs.getLong("period_id"), rs.getString("run_type"),
                rs.getString("status"), rs.getInt("employee_count"), rs.getBigDecimal("total_gross"),
                rs.getBigDecimal("total_deductions"), rs.getBigDecimal("total_net"),
                rs.getObject("calculated_at", LocalDateTime.class), rs.getString("approved_by"));
    }
}
