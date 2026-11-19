package com.hrms.reporting;

import static com.hrms.reporting.ReportingModels.Table;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportingRepository {

    private final JdbcTemplate jdbc;

    public ReportingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Table headcount(LocalDate asOf, Long deptId, String locationCode, boolean matchLocation) {
        return query("""
                WITH filters AS (SELECT ?::date AS as_of, ?::bigint AS dept_id, ?::varchar AS location_code, ?::boolean AS match_location)
                SELECT d.dept_id, d.dept_name, d.cost_center, l.location_id, l.location_name,
                       l.city, l.state_province, COUNT(*) AS headcount,
                       COUNT(*) FILTER (WHERE e.employment_type = 'FULL_TIME') AS ft_count,
                       COUNT(*) FILTER (WHERE e.employment_type = 'PART_TIME') AS pt_count,
                       COUNT(*) FILTER (WHERE e.employment_type = 'CONTRACT') AS contract_count,
                       COUNT(*) FILTER (WHERE e.gender = 'M') AS male_count,
                       COUNT(*) FILTER (WHERE e.gender = 'F') AS female_count,
                       -- Oracle MONTHS_BETWEEN treats month-ends as whole months.
                       ROUND(AVG(
                           (EXTRACT(YEAR FROM f.as_of) - EXTRACT(YEAR FROM e.hire_date)) * 12
                           + EXTRACT(MONTH FROM f.as_of) - EXTRACT(MONTH FROM e.hire_date)
                           + CASE WHEN EXTRACT(DAY FROM f.as_of + 1) = 1
                                     AND EXTRACT(DAY FROM e.hire_date + 1) = 1 THEN 0
                                  ELSE (EXTRACT(DAY FROM f.as_of) - EXTRACT(DAY FROM e.hire_date)) / 31
                             END
                       ) / 12, 1) AS avg_tenure_years
                FROM employees e
                JOIN departments d ON d.dept_id = e.dept_id
                LEFT JOIN locations l ON l.location_code = e.location_code
                CROSS JOIN filters f
                WHERE e.employment_status = 'ACTIVE' AND e.hire_date <= f.as_of
                  AND (e.termination_date IS NULL OR e.termination_date > f.as_of)
                  AND (f.dept_id IS NULL OR e.dept_id = f.dept_id)
                  AND (NOT f.match_location OR e.location_code = f.location_code)
                GROUP BY d.dept_id, l.location_id, l.location_name, l.city, l.state_province
                ORDER BY d.dept_name, l.location_name, d.dept_id, l.location_id
                """, asOf, deptId, locationCode, matchLocation);
    }

    Table compensation(Long deptId, Long gradeId) {
        return query("""
                WITH filters AS (SELECT ?::bigint AS dept_id, ?::bigint AS grade_id)
                SELECT d.dept_id, d.dept_name, g.grade_id, g.grade_name, j.job_title,
                       COUNT(*) AS emp_count, g.min_salary AS grade_min, g.max_salary AS grade_max,
                       MIN(sr.base_salary) AS actual_min, MAX(sr.base_salary) AS actual_max,
                       ROUND(AVG(sr.base_salary), 2) AS avg_salary,
                       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY sr.base_salary)::numeric, 2) AS median_salary,
                       ROUND(AVG(sr.base_salary) * 100 / NULLIF((g.min_salary + g.max_salary) / 2, 0), 1) AS compa_ratio
                FROM employees e
                JOIN departments d ON d.dept_id = e.dept_id
                JOIN job_titles j ON j.job_id = e.job_id
                JOIN job_grades g ON g.grade_id = j.grade_id
                JOIN salary_records sr ON sr.emp_id = e.emp_id AND sr.active_flag = 'Y'
                CROSS JOIN filters f
                WHERE e.employment_status = 'ACTIVE'
                  AND (f.dept_id IS NULL OR e.dept_id = f.dept_id)
                  AND (f.grade_id IS NULL OR g.grade_id = f.grade_id)
                GROUP BY d.dept_id, g.grade_id, j.job_id
                ORDER BY d.dept_name, g.grade_name, j.job_title, d.dept_id, g.grade_id, j.job_id
                """, deptId, gradeId);
    }

    Table turnover(LocalDate startDate, LocalDate endDate, Long deptId) {
        return query("""
                WITH filters AS (SELECT ?::date AS start_date, ?::date AS end_date, ?::bigint AS dept_id),
                snapshot AS (
                    SELECT e.dept_id, e.employment_status, e.termination_reason, e.termination_date, e.hire_date,
                           e.termination_date BETWEEN f.start_date AND f.end_date AS left_in_period
                    FROM employees e CROSS JOIN filters f
                    WHERE e.hire_date <= f.end_date AND (f.dept_id IS NULL OR e.dept_id = f.dept_id)
                )
                SELECT d.dept_id, d.dept_name,
                       COUNT(*) FILTER (WHERE e.left_in_period) AS terminations,
                       COUNT(*) FILTER (WHERE e.employment_status = 'ACTIVE') AS current_hc,
                       ROUND(COUNT(*) FILTER (WHERE e.left_in_period) * 100.0 / COUNT(*), 1) AS turnover_pct,
                       COUNT(*) FILTER (WHERE e.left_in_period AND e.termination_reason = 'VOLUNTARY') AS voluntary,
                       COUNT(*) FILTER (WHERE e.left_in_period AND e.termination_reason <> 'VOLUNTARY') AS involuntary,
                       ROUND(AVG(
                           (EXTRACT(YEAR FROM e.termination_date) - EXTRACT(YEAR FROM e.hire_date)) * 12
                           + EXTRACT(MONTH FROM e.termination_date) - EXTRACT(MONTH FROM e.hire_date)
                           + CASE WHEN EXTRACT(DAY FROM e.termination_date + 1) = 1
                                     AND EXTRACT(DAY FROM e.hire_date + 1) = 1 THEN 0
                                  ELSE (EXTRACT(DAY FROM e.termination_date) - EXTRACT(DAY FROM e.hire_date)) / 31
                             END
                       ) FILTER (WHERE e.left_in_period) / 12, 1) AS avg_tenure_at_exit
                FROM snapshot e JOIN departments d ON d.dept_id = e.dept_id
                GROUP BY d.dept_id
                ORDER BY turnover_pct DESC, d.dept_name, d.dept_id
                """, startDate, endDate, deptId);
    }

    Table newHires(LocalDate startDate, LocalDate endDate, Long deptId) {
        return query("""
                SELECT e.emp_number, e.first_name || ' ' || e.last_name AS emp_name,
                       e.hire_date, d.dept_id, d.dept_name, j.job_title, l.location_name,
                       e.employment_type, sr.base_salary, e.manager_emp_id,
                       m.first_name || ' ' || m.last_name AS manager_name
                FROM employees e
                JOIN departments d ON d.dept_id = e.dept_id
                JOIN job_titles j ON j.job_id = e.job_id
                LEFT JOIN locations l ON l.location_code = e.location_code
                LEFT JOIN employees m ON m.emp_id = e.manager_emp_id
                LEFT JOIN salary_records sr ON sr.emp_id = e.emp_id AND sr.active_flag = 'Y'
                WHERE e.hire_date BETWEEN ? AND ? AND (?::bigint IS NULL OR e.dept_id = ?)
                ORDER BY e.hire_date DESC, e.emp_number
                """, startDate, endDate, deptId, deptId);
    }

    Table leaveUtilization(int year, Long deptId) {
        return query("""
                SELECT d.dept_id, d.dept_name, lt.leave_type_name, COUNT(DISTINCT lb.emp_id) AS emp_count,
                       ROUND(AVG(lb.opening_balance + lb.accrued), 1) AS avg_entitled,
                       ROUND(AVG(lb.used), 1) AS avg_used,
                       ROUND(AVG(lb.available), 1) AS avg_available,
                       ROUND(AVG(lb.pending), 1) AS avg_pending,
                       ROUND(AVG(lb.used) * 100.0 / NULLIF(AVG(lb.opening_balance + lb.accrued), 0), 1) AS utilization_pct
                FROM leave_balances lb
                JOIN employees e ON e.emp_id = lb.emp_id
                JOIN departments d ON d.dept_id = e.dept_id
                JOIN leave_types lt ON lt.leave_type_id = lb.leave_type_id
                WHERE lb.calendar_year = ? AND e.employment_status = 'ACTIVE'
                  AND (?::bigint IS NULL OR e.dept_id = ?)
                GROUP BY d.dept_id, lt.leave_type_id
                ORDER BY d.dept_name, lt.leave_type_name, d.dept_id, lt.leave_type_id
                """, year, deptId, deptId);
    }

    Table payrollSummary(long periodId) {
        return query("""
                SELECT d.dept_id, d.dept_name, COUNT(DISTINCT pd.emp_id) AS emp_count,
                       SUM(CASE WHEN pd.element_type = 'EARNING' THEN pd.amount ELSE 0 END) AS total_gross,
                       SUM(CASE WHEN pd.element_id = 100 THEN ABS(pd.amount) ELSE 0 END) AS total_fed_tax,
                       SUM(CASE WHEN pd.element_id = 101 THEN ABS(pd.amount) ELSE 0 END) AS total_state_tax,
                       SUM(CASE WHEN pd.element_id = 102 THEN ABS(pd.amount) ELSE 0 END) AS total_ss,
                       SUM(CASE WHEN pd.element_id = 103 THEN ABS(pd.amount) ELSE 0 END) AS total_medicare,
                       SUM(CASE WHEN pd.element_type IN ('DEDUCTION', 'TAX', 'BENEFIT')
                                THEN ABS(pd.amount) ELSE 0 END) AS total_deductions,
                       SUM(pd.amount) AS total_net
                FROM payroll_details pd
                JOIN payroll_runs pr ON pr.run_id = pd.run_id
                JOIN employees e ON e.emp_id = pd.emp_id
                JOIN departments d ON d.dept_id = e.dept_id
                WHERE pr.period_id = ? AND pd.status <> 'ERROR'
                GROUP BY d.dept_id
                ORDER BY d.dept_name, d.dept_id
                """, periodId);
    }

    Table eeo(LocalDate asOf) {
        return query("""
                SELECT j.eeo_category, COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE e.gender = 'M') AS male,
                       COUNT(*) FILTER (WHERE e.gender = 'F') AS female,
                       COUNT(*) FILTER (WHERE e.gender = 'O') AS other_gender,
                       COUNT(*) FILTER (WHERE e.gender IS NULL) AS not_disclosed,
                       ROUND(COUNT(*) FILTER (WHERE e.gender = 'F') * 100.0 / COUNT(*), 1) AS female_pct
                FROM employees e JOIN job_titles j ON j.job_id = e.job_id
                WHERE e.employment_status = 'ACTIVE' AND e.hire_date <= ?
                GROUP BY j.eeo_category
                ORDER BY j.eeo_category
                """, asOf);
    }

    private Table query(String sql, Object... params) {
        return jdbc.query(sql, ReportingRepository::readTable, params);
    }

    private static Table readTable(ResultSet result) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            columns.add(metadata.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (result.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                Object value = result.getObject(i + 1);
                row.put(columns.get(i), value instanceof Date date ? date.toLocalDate() : value);
            }
            rows.add(row);
        }
        return new Table(columns, rows);
    }
}
