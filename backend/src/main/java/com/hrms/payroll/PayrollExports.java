package com.hrms.payroll;

import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PayrollExports {
    private final PayrollService payroll;
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final Clock clock;

    PayrollExports(PayrollService payroll, JdbcTemplate jdbc, PermissionService permissions, Clock clock) {
        this.payroll = payroll;
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String register(long runId, CurrentUser user) {
        payroll.getRun(runId, user);
        List<String> rows = jdbc.query("""
                SELECT e.emp_number, e.first_name || ' ' || e.last_name AS employee_name, dept.dept_name,
                       coalesce(sum(d.amount) FILTER (WHERE d.element_type = 'EARNING'), 0) AS gross,
                       coalesce(-sum(d.amount) FILTER (WHERE d.element_id = 100), 0) AS federal,
                       coalesce(-sum(d.amount) FILTER (WHERE d.element_id = 101), 0) AS state,
                       coalesce(-sum(d.amount) FILTER (WHERE d.element_id = 102), 0) AS social_security,
                       coalesce(-sum(d.amount) FILTER (WHERE d.element_id = 103), 0) AS medicare,
                       coalesce(-sum(d.amount) FILTER (WHERE d.element_type IN ('DEDUCTION','BENEFIT')), 0) AS deductions,
                       sum(d.amount) AS net
                FROM payroll_details d JOIN employees e USING (emp_id) JOIN departments dept USING (dept_id)
                WHERE d.run_id = ? AND d.status = 'CALCULATED'
                GROUP BY e.emp_number, e.last_name, e.first_name, dept.dept_name
                ORDER BY e.last_name, e.first_name, e.emp_number
                """, (rs, row) -> String.join(",", csvCell(rs.getString("emp_number")),
                csvCell(rs.getString("employee_name")), csvCell(rs.getString("dept_name")),
                money(rs, "gross"), money(rs, "federal"), money(rs, "state"), money(rs, "social_security"),
                money(rs, "medicare"), money(rs, "deductions"), money(rs, "net")), runId);
        var file = new StringBuilder(
                "EMP_NUMBER,EMPLOYEE_NAME,DEPARTMENT,GROSS_PAY,FED_TAX,STATE_TAX,SS_TAX,MEDICARE,DEDUCTIONS,NET_PAY\r\n");
        rows.forEach(row -> file.append(row).append("\r\n"));
        return file.toString();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String glJournal(long runId, CurrentUser user) {
        payroll.getRun(runId, user);
        List<String> rows = jdbc.query("""
                SELECT dept.cost_center, pe.gl_account_code, d.element_type, abs(sum(d.amount)) AS total, pp.period_name
                FROM payroll_details d
                JOIN payroll_runs r USING (run_id) JOIN pay_periods pp USING (period_id)
                JOIN employees e USING (emp_id) JOIN departments dept USING (dept_id)
                JOIN pay_elements pe USING (element_id)
                WHERE d.run_id = ? AND d.status = 'CALCULATED' AND pe.gl_account_code IS NOT NULL
                GROUP BY dept.cost_center, pe.gl_account_code, d.element_type, pp.period_name
                ORDER BY dept.cost_center, pe.gl_account_code, d.element_type
                """, (rs, row) -> {
            String amount = money(rs, "total");
            boolean earning = "EARNING".equals(rs.getString("element_type"));
            return String.join("|", "D", glField(rs.getString("cost_center")), glField(rs.getString("gl_account_code")),
                    earning ? amount : "0.00", earning ? "0.00" : amount, "Payroll " + glField(rs.getString("period_name")),
                    "RUN-" + runId);
        }, runId);
        var file = new StringBuilder("H|HRMS_PAYROLL|").append(LocalDate.now(clock)).append('|').append(runId).append('\n');
        rows.forEach(row -> file.append(row).append('\n'));
        return file.append("T|").append(rows.size()).append('\n').toString();
    }

    String benefits(CurrentUser user) {
        permissions.require(user, Module.PAYROLL, Action.VIEW);
        List<String> rows = jdbc.query("""
                SELECT e.emp_number, e.first_name, e.last_name, e.date_of_birth, e.hire_date,
                       e.employment_status, e.marital_status, e.gender, d.first_name AS dep_first_name,
                       d.last_name AS dep_last_name, d.relationship, d.date_of_birth AS dep_dob
                FROM employees e LEFT JOIN employee_dependents d ON e.emp_id = d.emp_id AND d.active_flag = 'Y'
                WHERE e.employment_status = 'ACTIVE' AND e.active_flag = 'Y'
                ORDER BY e.emp_number, d.dependent_id
                """, (rs, row) -> fixed(rs, "emp_number", 10) + fixed(rs, "first_name", 30) + fixed(rs, "last_name", 30)
                + fixed(rs, "date_of_birth", 10) + fixed(rs, "hire_date", 10) + fixed(rs, "employment_status", 12)
                + fixed(rs, "marital_status", 10) + fixed(rs, "gender", 1) + fixed(rs, "dep_first_name", 30)
                + fixed(rs, "dep_last_name", 30) + fixed(rs, "relationship", 20) + fixed(rs, "dep_dob", 10));
        var file = new StringBuilder();
        rows.forEach(row -> file.append(row).append('\n'));
        return file.toString();
    }

    private static String money(ResultSet rs, String column) throws SQLException {
        BigDecimal amount = rs.getBigDecimal(column);
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String csvCell(String value) {
        // Quoting alone doesn't prevent spreadsheet formula interpretation.
        String stripped = value.stripLeading();
        if (value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")
                || (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0)) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String glField(String value) {
        return value == null ? "" : value.replaceAll("[|\\p{Cntrl}]", " ");
    }

    private static String fixed(ResultSet rs, String column, int width) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            return " ".repeat(width);
        }
        value = value.replaceAll("\\p{Cntrl}", " ");
        int length = Math.min(width, value.codePointCount(0, value.length()));
        return value.substring(0, value.offsetByCodePoints(0, length)) + " ".repeat(width - length);
    }
}
