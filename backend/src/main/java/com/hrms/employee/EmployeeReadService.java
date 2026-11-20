package com.hrms.employee;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Employee read model. Also the in-process read API other modules use instead of calling the employee
 * service (OQ-01: payroll reads employee status here, never through a reverse call).
 * Default reads are active-only: {@code employment_status = 'ACTIVE' AND active_flag} (D-03, FRM-031).
 */
@Service
public class EmployeeReadService {

    static final String ACTIVE_FILTER = "e.employment_status = 'ACTIVE' AND e.active_flag";

    private static final String SELECT = """
            SELECT e.emp_id, e.emp_number, e.first_name, e.last_name, e.email, e.employment_status,
                   e.employment_type, e.active_flag, e.hire_date, e.manager_emp_id,
                   CASE WHEN m.emp_id IS NULL THEN NULL ELSE m.first_name || ' ' || m.last_name END AS manager_name,
                   e.location_code, l.location_name, e.dept_id, d.dept_name, e.job_id, j.job_title
            FROM hrms.employees e
            JOIN hrms.departments d ON d.dept_id = e.dept_id
            JOIN hrms.job_titles j ON j.job_id = e.job_id
            LEFT JOIN hrms.employees m ON m.emp_id = e.manager_emp_id
            LEFT JOIN hrms.locations l ON l.location_code = e.location_code
            """;

    private final JdbcClient jdbc;

    public EmployeeReadService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeView> findById(long empId, boolean includeInactive) {
        String sql = SELECT + " WHERE e.emp_id = :empId" + (includeInactive ? "" : " AND " + ACTIVE_FILTER);
        return jdbc.sql(sql).param("empId", empId).query(EmployeeReadService::map).optional();
    }

    /** True when the employee exists with {@code employment_status = 'ACTIVE'} (PKG_EMPLOYEE.is_active). */
    public boolean isActive(long empId) {
        return jdbc.sql("SELECT count(*) FROM hrms.employees e WHERE e.emp_id = :empId AND e.employment_status = 'ACTIVE'")
                .param("empId", empId).query(Long.class).single() > 0;
    }

    public EmployeePage search(EmployeeSearch s) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (!s.includeInactive()) {
            where.add(ACTIVE_FILTER);
        }
        if (s.lastName() != null) {
            where.add("starts_with(upper(e.last_name), upper(:lastName))");
            params.put("lastName", s.lastName());
        }
        if (s.firstName() != null) {
            where.add("starts_with(upper(e.first_name), upper(:firstName))");
            params.put("firstName", s.firstName());
        }
        if (s.deptId() != null) {
            where.add("e.dept_id = :deptId");
            params.put("deptId", s.deptId());
        }
        if (s.managerEmpId() != null) {
            where.add("e.manager_emp_id = :managerEmpId");
            params.put("managerEmpId", s.managerEmpId());
        }
        if (s.status() != null) {
            where.add("e.employment_status = :status");
            params.put("status", s.status());
        }
        if (s.locationCode() != null) {
            where.add("e.location_code = :locationCode");
            params.put("locationCode", s.locationCode());
        }
        if (s.hireDateFrom() != null) {
            where.add("e.hire_date >= :hireDateFrom");
            params.put("hireDateFrom", s.hireDateFrom());
        }
        if (s.hireDateTo() != null) {
            where.add("e.hire_date <= :hireDateTo");
            params.put("hireDateTo", s.hireDateTo());
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        long total = jdbc.sql("SELECT count(*) FROM hrms.employees e" + clause).params(params)
                .query(Long.class).single();
        List<EmployeeView> items = jdbc.sql(SELECT + clause
                        + " ORDER BY e.last_name, e.first_name, e.emp_id LIMIT :limit OFFSET :offset")
                .params(params).param("limit", s.size()).param("offset", (long) s.page() * s.size())
                .query(EmployeeReadService::map).list();
        return new EmployeePage(items, s.page(), s.size(), total);
    }

    private static EmployeeView map(ResultSet rs, int n) throws SQLException {
        return new EmployeeView(rs.getLong("emp_id"), rs.getString("emp_number"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("email"), rs.getString("employment_status"),
                rs.getString("employment_type"), rs.getBoolean("active_flag"),
                rs.getObject("hire_date", LocalDate.class), (Long) rs.getObject("manager_emp_id", Long.class),
                rs.getString("manager_name"), rs.getString("location_code"), rs.getString("location_name"),
                rs.getLong("dept_id"), rs.getString("dept_name"), rs.getLong("job_id"), rs.getString("job_title"));
    }
}
