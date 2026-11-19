package com.hrms.platform.employee;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Shared, read-only employee/manager/department lookups. Modules other than Employee must use
 * this instead of reading EMPLOYEES directly; writes belong to the Employee module.
 */
@Service
public class EmployeeLookup {

    private static final String SELECT = """
            SELECT e.emp_id, e.emp_number, e.first_name, e.last_name, e.email, e.dept_id, d.dept_name,
                   e.job_id, j.job_title, j.grade_id, e.manager_emp_id, e.location_code, e.hire_date,
                   e.employment_status, e.active_flag
            FROM employees e
            JOIN departments d ON d.dept_id = e.dept_id
            JOIN job_titles j ON j.job_id = e.job_id
            """;

    private final JdbcTemplate jdbc;

    public EmployeeLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeSummary> findById(long empId) {
        return jdbc.query(SELECT + " WHERE e.emp_id = ?", EmployeeLookup::map, empId).stream().findFirst();
    }

    /** Active employee by login e-mail (case-insensitive); duplicates resolve to lowest id. */
    public Optional<EmployeeSummary> findActiveByEmail(String email) {
        return jdbc.query(SELECT + " WHERE lower(e.email) = lower(?) AND e.active_flag = 'Y' ORDER BY e.emp_id",
                EmployeeLookup::map, email).stream().findFirst();
    }

    public List<EmployeeSummary> directReports(long managerEmpId) {
        return jdbc.query(SELECT + " WHERE e.manager_emp_id = ? AND e.employment_status = 'ACTIVE'"
                + " ORDER BY e.last_name, e.first_name", EmployeeLookup::map, managerEmpId);
    }

    public List<EmployeeSummary> activeEmployees() {
        return jdbc.query(SELECT + " WHERE e.employment_status = 'ACTIVE' AND e.active_flag = 'Y' ORDER BY e.emp_id",
                EmployeeLookup::map);
    }

    public boolean isManagerOf(long managerEmpId, long empId) {
        return findById(empId).map(e -> e.managerEmpId() != null && e.managerEmpId() == managerEmpId).orElse(false);
    }

    private static EmployeeSummary map(ResultSet rs, int row) throws SQLException {
        String first = rs.getString("first_name");
        String last = rs.getString("last_name");
        return new EmployeeSummary(
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                first,
                last,
                first + " " + last,
                rs.getString("email"),
                rs.getLong("dept_id"),
                rs.getString("dept_name"),
                rs.getLong("job_id"),
                rs.getString("job_title"),
                rs.getInt("grade_id"),
                rs.getObject("manager_emp_id", Long.class),
                rs.getString("location_code"),
                rs.getObject("hire_date", java.time.LocalDate.class),
                rs.getString("employment_status"),
                "Y".equals(rs.getString("active_flag")));
    }
}
