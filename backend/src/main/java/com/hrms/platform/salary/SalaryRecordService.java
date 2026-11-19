package com.hrms.platform.salary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single owner of SALARY_RECORDS writes (inventory §8): used by Employee (hire/promotion),
 * Payroll (salary changes) and Performance (merit increases). Exactly one active record per
 * employee; changing salary end-dates the current record the day before the new effective date.
 */
@Service
public class SalaryRecordService {

    private static final String SELECT = """
            SELECT salary_id, emp_id, effective_date, end_date, base_salary, currency_code, pay_frequency,
                   salary_basis, change_reason, change_pct, active_flag
            FROM salary_records
            """;

    private final JdbcTemplate jdbc;

    public SalaryRecordService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SalaryRecord> current(long empId) {
        return jdbc.query(SELECT + " WHERE emp_id = ? AND active_flag = 'Y' ORDER BY effective_date DESC LIMIT 1",
                SalaryRecordService::map, empId).stream().findFirst();
    }

    public List<SalaryRecord> history(long empId) {
        return jdbc.query(SELECT + " WHERE emp_id = ? ORDER BY effective_date DESC, salary_id DESC",
                SalaryRecordService::map, empId);
    }

    /** Creates a new active salary record, closing the previous one. Returns the new salary id. */
    @Transactional
    public long changeSalary(long empId, BigDecimal newSalary, LocalDate effectiveDate, String reason,
                             String payFrequency, String user) {
        Optional<SalaryRecord> previous = current(empId);
        BigDecimal changePct = previous
                .filter(p -> p.baseSalary().signum() > 0)
                .map(p -> newSalary.subtract(p.baseSalary()).multiply(BigDecimal.valueOf(100))
                        .divide(p.baseSalary(), 2, RoundingMode.HALF_UP))
                .orElse(null);
        previous.ifPresent(p -> jdbc.update("""
                UPDATE salary_records SET end_date = ?, active_flag = 'N', modified_by = ?, modified_date = now()
                WHERE salary_id = ?
                """, effectiveDate.minusDays(1), user, p.salaryId()));
        String frequency = payFrequency != null ? payFrequency
                : previous.map(SalaryRecord::payFrequency).orElse("MONTHLY");
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO salary_records (emp_id, effective_date, base_salary, currency_code, pay_frequency,
                                                salary_basis, change_reason, change_pct, active_flag, created_by)
                    VALUES (?, ?, ?, 'USD', ?, 'ANNUAL', ?, ?, 'Y', ?)
                    """, new String[] {"salary_id"});
            ps.setLong(1, empId);
            ps.setObject(2, effectiveDate);
            ps.setBigDecimal(3, newSalary);
            ps.setString(4, frequency);
            ps.setString(5, reason);
            ps.setBigDecimal(6, changePct);
            ps.setString(7, user);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private static SalaryRecord map(ResultSet rs, int row) throws SQLException {
        return new SalaryRecord(
                rs.getLong("salary_id"),
                rs.getLong("emp_id"),
                rs.getObject("effective_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBigDecimal("base_salary"),
                rs.getString("currency_code"),
                rs.getString("pay_frequency"),
                rs.getString("salary_basis"),
                rs.getString("change_reason"),
                rs.getBigDecimal("change_pct"),
                "Y".equals(rs.getString("active_flag")));
    }
}
