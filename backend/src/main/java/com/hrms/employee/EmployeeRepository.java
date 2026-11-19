package com.hrms.employee;

import com.hrms.employee.EmployeeModels.Dependent;
import com.hrms.employee.EmployeeModels.EmergencyContact;
import com.hrms.employee.EmployeeModels.EmployeeHistory;
import com.hrms.employee.EmployeeModels.EmployeeRow;
import com.hrms.employee.EmployeeModels.HeadcountRow;
import com.hrms.employee.EmployeeModels.HistoryEntry;
import com.hrms.employee.EmployeeModels.JobRef;
import com.hrms.employee.EmployeeModels.OrgChartNode;
import com.hrms.employee.EmployeeModels.RefItem;
import com.hrms.platform.employee.EmployeeSummary;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeRepository {

    private static final String EMPLOYEE_SELECT = """
            SELECT e.*, d.dept_name, j.job_title, j.grade_id, l.location_id
            FROM employees e
            JOIN departments d ON d.dept_id = e.dept_id
            JOIN job_titles j ON j.job_id = e.job_id
            LEFT JOIN locations l ON l.location_code = e.location_code
            """;

    private final JdbcTemplate jdbc;

    public EmployeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeRow> find(long empId) {
        return jdbc.query(EMPLOYEE_SELECT + " WHERE e.emp_id = ?", EmployeeRepository::mapRow, empId)
                .stream().findFirst();
    }

    public Optional<EmployeeRow> findByNumber(String empNumber) {
        return jdbc.query(EMPLOYEE_SELECT + " WHERE e.emp_number = ?", EmployeeRepository::mapRow, empNumber)
                .stream().findFirst();
    }

    /** Row-locks the employee for a lifecycle change (replaces the form's LOCK_RECORD). */
    public Optional<EmployeeRow> lock(long empId) {
        List<Long> ids = jdbc.queryForList("SELECT emp_id FROM employees WHERE emp_id = ? FOR UPDATE",
                Long.class, empId);
        return ids.isEmpty() ? Optional.empty() : find(empId);
    }

    public record SearchFilter(String lastName, String firstName, Long deptId, Long locationId, String status,
                               LocalDate hiredFrom, LocalDate hiredTo, int limit) {
    }

    public List<EmployeeSummary> search(SearchFilter f) {
        StringBuilder sql = new StringBuilder(EMPLOYEE_SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (f.lastName() != null) {
            sql.append(" AND upper(e.last_name) LIKE upper(?) ESCAPE '\\'");
            args.add(likePrefix(f.lastName()));
        }
        if (f.firstName() != null) {
            sql.append(" AND upper(e.first_name) LIKE upper(?) ESCAPE '\\'");
            args.add(likePrefix(f.firstName()));
        }
        if (f.deptId() != null) {
            sql.append(" AND e.dept_id = ?");
            args.add(f.deptId());
        }
        if (f.locationId() != null) {
            sql.append(" AND l.location_id = ?");
            args.add(f.locationId());
        }
        if (f.status() != null) {
            sql.append(" AND e.employment_status = ?");
            args.add(f.status());
        }
        if (f.hiredFrom() != null) {
            sql.append(" AND e.hire_date >= ?");
            args.add(f.hiredFrom());
        }
        if (f.hiredTo() != null) {
            sql.append(" AND e.hire_date <= ?");
            args.add(f.hiredTo());
        }
        sql.append(" ORDER BY e.last_name, e.first_name, e.emp_id LIMIT ?");
        args.add(f.limit());
        return jdbc.query(sql.toString(), (rs, n) -> toSummary(mapRow(rs, n)), args.toArray());
    }

    private static String likePrefix(String value) {
        return value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public String nextEmpNumber() {
        Long n = jdbc.queryForObject("SELECT nextval('seq_emp_number')", Long.class);
        return String.format("EMP-%06d", n);
    }

    public boolean activeEmailTaken(String email, Long excludeEmpId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM employees
                WHERE lower(email) = lower(?) AND active_flag = 'Y' AND emp_id <> ?
                """, Integer.class, email, excludeEmpId == null ? -1L : excludeEmpId);
        return count != null && count > 0;
    }

    public record NewEmployee(String empNumber, String firstName, String middleName, String lastName, String email,
                              String phoneWork, String phoneMobile, LocalDate dateOfBirth, String gender,
                              String maritalStatus, LocalDate hireDate, long deptId, long jobId, Long managerEmpId,
                              String locationCode, String employmentType) {
    }

    public long insert(NewEmployee e, String user) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO employees (emp_number, first_name, middle_name, last_name, email, phone_work,
                        phone_mobile, date_of_birth, gender, marital_status, hire_date, dept_id, job_id,
                        manager_emp_id, location_code, employment_type, employment_status, active_flag, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'Y', ?)
                    """, new String[] {"emp_id"});
            ps.setString(1, e.empNumber());
            ps.setString(2, e.firstName());
            ps.setString(3, e.middleName());
            ps.setString(4, e.lastName());
            ps.setString(5, e.email());
            ps.setString(6, e.phoneWork());
            ps.setString(7, e.phoneMobile());
            ps.setObject(8, e.dateOfBirth());
            ps.setString(9, e.gender());
            ps.setString(10, e.maritalStatus());
            ps.setObject(11, e.hireDate());
            ps.setLong(12, e.deptId());
            ps.setLong(13, e.jobId());
            ps.setObject(14, e.managerEmpId());
            ps.setString(15, e.locationCode());
            ps.setString(16, e.employmentType());
            ps.setString(17, user);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updatePersonal(EmployeeRow r, String user) {
        jdbc.update("""
                UPDATE employees SET first_name = ?, middle_name = ?, last_name = ?, email = ?, phone_work = ?,
                    phone_mobile = ?, address_line1 = ?, address_line2 = ?, city = ?, state_province = ?,
                    postal_code = ?, country_code = ?, modified_by = ?, modified_date = now()
                WHERE emp_id = ?
                """, r.firstName(), r.middleName(), r.lastName(), r.email(), r.phoneWork(), r.phoneMobile(),
                r.addressLine1(), r.addressLine2(), r.city(), r.state(), r.postalCode(), r.country(), user,
                r.empId());
    }

    public void updateAssignment(long empId, long deptId, long jobId, Long managerEmpId, String locationCode,
                                 String user) {
        jdbc.update("""
                UPDATE employees SET dept_id = ?, job_id = ?, manager_emp_id = ?, location_code = ?,
                    modified_by = ?, modified_date = now()
                WHERE emp_id = ?
                """, deptId, jobId, managerEmpId, locationCode, user, empId);
    }

    public void markTerminated(long empId, LocalDate date, String reason, String user) {
        jdbc.update("""
                UPDATE employees SET employment_status = 'TERMINATED', active_flag = 'N', termination_date = ?,
                    termination_reason = ?, modified_by = ?, modified_date = now()
                WHERE emp_id = ?
                """, date, reason, user, empId);
    }

    public void markRehired(long empId, LocalDate hireDate, long deptId, long jobId, Long managerEmpId,
                            String locationCode, String user) {
        jdbc.update("""
                UPDATE employees SET employment_status = 'ACTIVE', active_flag = 'Y', hire_date = ?,
                    termination_date = NULL, termination_reason = NULL, dept_id = ?, job_id = ?,
                    manager_emp_id = ?, location_code = ?, modified_by = ?, modified_date = now()
                WHERE emp_id = ?
                """, hireDate, deptId, jobId, managerEmpId, locationCode, user, empId);
    }

    public void insertHistory(HistoryEntry h, String user) {
        jdbc.update("""
                INSERT INTO employee_history (emp_id, change_type, effective_date, old_dept_id, new_dept_id,
                    old_job_id, new_job_id, old_manager_id, new_manager_id, old_salary, new_salary,
                    old_location, new_location, reason_code, comments, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, h.empId(), h.changeType(), h.effectiveDate(), h.oldDeptId(), h.newDeptId(), h.oldJobId(),
                h.newJobId(), h.oldManagerId(), h.newManagerId(), h.oldSalary(), h.newSalary(), h.oldLocation(),
                h.newLocation(), h.reasonCode(), h.comments(), user);
    }

    public List<EmployeeHistory> history(long empId, boolean includeSalary) {
        return jdbc.query("""
                SELECT * FROM employee_history WHERE emp_id = ? ORDER BY effective_date DESC, hist_id DESC
                """, (rs, n) -> {
                    Map<String, Object> oldValues = new LinkedHashMap<>();
                    Map<String, Object> newValues = new LinkedHashMap<>();
                    put(oldValues, "deptId", rs.getObject("old_dept_id", Long.class));
                    put(newValues, "deptId", rs.getObject("new_dept_id", Long.class));
                    put(oldValues, "jobId", rs.getObject("old_job_id", Long.class));
                    put(newValues, "jobId", rs.getObject("new_job_id", Long.class));
                    put(oldValues, "managerEmpId", rs.getObject("old_manager_id", Long.class));
                    put(newValues, "managerEmpId", rs.getObject("new_manager_id", Long.class));
                    put(oldValues, "locationCode", rs.getString("old_location"));
                    put(newValues, "locationCode", rs.getString("new_location"));
                    if (includeSalary) {
                        put(oldValues, "salary", rs.getBigDecimal("old_salary"));
                        put(newValues, "salary", rs.getBigDecimal("new_salary"));
                    }
                    String comments = rs.getString("comments");
                    Timestamp created = rs.getTimestamp("created_date");
                    return new EmployeeHistory(rs.getLong("hist_id"), rs.getString("change_type"),
                            rs.getObject("effective_date", LocalDate.class), oldValues, newValues,
                            comments != null ? comments : rs.getString("reason_code"), rs.getString("created_by"),
                            created == null ? null : created.toLocalDateTime());
                }, empId);
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** Ancestors of the employee, nearest first; depth-capped so legacy cycles cannot loop. */
    public List<Long> managementChain(long empId) {
        return jdbc.queryForList("""
                WITH RECURSIVE chain(manager_emp_id, depth) AS (
                    SELECT manager_emp_id, 1 FROM employees WHERE emp_id = ?
                    UNION ALL
                    SELECT e.manager_emp_id, c.depth + 1 FROM employees e JOIN chain c ON e.emp_id = c.manager_emp_id
                    WHERE c.depth < 20)
                SELECT manager_emp_id FROM chain WHERE manager_emp_id IS NOT NULL ORDER BY depth
                """, Long.class, empId);
    }

    public List<OrgChartNode> orgChart(long empId) {
        return jdbc.query("""
                WITH RECURSIVE tree(emp_id, manager_emp_id, lvl) AS (
                    SELECT emp_id, manager_emp_id, 1 FROM employees
                    WHERE manager_emp_id = ? AND employment_status = 'ACTIVE'
                    UNION ALL
                    SELECT e.emp_id, e.manager_emp_id, t.lvl + 1 FROM employees e JOIN tree t ON e.manager_emp_id = t.emp_id
                    WHERE e.employment_status = 'ACTIVE' AND t.lvl < 10)
                SELECT t.emp_id, t.manager_emp_id, t.lvl, e.first_name, e.last_name, j.job_title
                FROM tree t
                JOIN employees e ON e.emp_id = t.emp_id
                JOIN job_titles j ON j.job_id = e.job_id
                ORDER BY t.lvl, e.last_name, e.first_name
                """, (rs, n) -> new OrgChartNode(rs.getLong("emp_id"),
                        rs.getString("first_name") + " " + rs.getString("last_name"), rs.getString("job_title"),
                        rs.getObject("manager_emp_id", Long.class), rs.getInt("lvl")), empId);
    }

    public Optional<Boolean> departmentActive(long deptId) {
        return jdbc.query("SELECT active_flag FROM departments WHERE dept_id = ?",
                (rs, n) -> "Y".equals(rs.getString(1)), deptId).stream().findFirst();
    }

    public Optional<String> departmentLocation(long deptId) {
        return jdbc.query("SELECT location_code FROM departments WHERE dept_id = ?",
                (rs, n) -> Optional.ofNullable(rs.getString(1)), deptId).stream().findFirst()
                .flatMap(o -> o);
    }

    public Optional<JobRef> job(long jobId) {
        return jdbc.query("""
                SELECT j.job_id, j.grade_id, j.active_flag, g.grade_code, g.min_salary, g.max_salary
                FROM job_titles j JOIN job_grades g ON g.grade_id = j.grade_id WHERE j.job_id = ?
                """, (rs, n) -> new JobRef(rs.getLong("job_id"), rs.getInt("grade_id"), rs.getString("grade_code"),
                rs.getBigDecimal("min_salary"), rs.getBigDecimal("max_salary"),
                "Y".equals(rs.getString("active_flag"))), jobId).stream().findFirst();
    }

    public List<RefItem> departments() {
        return jdbc.query("""
                SELECT dept_id, dept_code, dept_name FROM departments WHERE active_flag = 'Y' ORDER BY dept_name
                """, (rs, n) -> new RefItem(rs.getLong(1), rs.getString(2), rs.getString(3), null));
    }

    public List<RefItem> locations() {
        return jdbc.query("""
                SELECT location_id, location_code, location_name FROM locations WHERE active_flag = 'Y'
                ORDER BY location_name
                """, (rs, n) -> new RefItem(rs.getLong(1), rs.getString(2), rs.getString(3), null));
    }

    public List<RefItem> jobTitles() {
        return jdbc.query("""
                SELECT job_id, job_code, job_title, grade_id FROM job_titles WHERE active_flag = 'Y'
                ORDER BY job_title
                """, (rs, n) -> new RefItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
    }

    public List<HeadcountRow> headcount(LocalDate asOf) {
        return jdbc.query("""
                SELECT d.dept_id, d.dept_name, count(e.emp_id) AS headcount
                FROM departments d
                LEFT JOIN employees e ON e.dept_id = d.dept_id AND e.hire_date <= ?
                    AND (e.termination_date IS NULL OR e.termination_date > ?)
                    AND (e.employment_status <> 'TERMINATED' OR e.termination_date IS NOT NULL)
                WHERE d.active_flag = 'Y'
                GROUP BY d.dept_id, d.dept_name
                ORDER BY d.dept_name
                """, (rs, n) -> new HeadcountRow(rs.getLong(1), rs.getString(2), rs.getInt(3)), asOf, asOf);
    }

    public List<Dependent> dependents(long empId) {
        return jdbc.query("""
                SELECT * FROM employee_dependents WHERE emp_id = ? AND active_flag = 'Y' ORDER BY dependent_id
                """, EmployeeRepository::mapDependent, empId);
    }

    public Optional<Dependent> dependent(long empId, long dependentId) {
        return jdbc.query("""
                SELECT * FROM employee_dependents WHERE emp_id = ? AND dependent_id = ? AND active_flag = 'Y'
                """, EmployeeRepository::mapDependent, empId, dependentId).stream().findFirst();
    }

    public long insertDependent(long empId, Dependent d, String user) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO employee_dependents (emp_id, first_name, last_name, relationship, date_of_birth,
                        gender, benefits_enrolled, active_flag, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', ?)
                    """, new String[] {"dependent_id"});
            ps.setLong(1, empId);
            ps.setString(2, d.firstName());
            ps.setString(3, d.lastName());
            ps.setString(4, d.relationship());
            ps.setObject(5, d.dateOfBirth());
            ps.setString(6, d.gender());
            ps.setString(7, Boolean.TRUE.equals(d.benefitsEligible()) ? "Y" : "N");
            ps.setString(8, user);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateDependent(long dependentId, Dependent d, String user) {
        jdbc.update("""
                UPDATE employee_dependents SET first_name = ?, last_name = ?, relationship = ?, date_of_birth = ?,
                    gender = ?, benefits_enrolled = ?, modified_by = ?, modified_date = now()
                WHERE dependent_id = ?
                """, d.firstName(), d.lastName(), d.relationship(), d.dateOfBirth(), d.gender(),
                Boolean.TRUE.equals(d.benefitsEligible()) ? "Y" : "N", user, dependentId);
    }

    public void deactivateDependent(long dependentId, String user) {
        jdbc.update("""
                UPDATE employee_dependents SET active_flag = 'N', modified_by = ?, modified_date = now()
                WHERE dependent_id = ?
                """, user, dependentId);
    }

    public List<EmergencyContact> emergencyContacts(long empId) {
        return jdbc.query("""
                SELECT * FROM emergency_contacts WHERE emp_id = ? AND active_flag = 'Y'
                ORDER BY priority_order, contact_id
                """, EmployeeRepository::mapContact, empId);
    }

    public Optional<EmergencyContact> emergencyContact(long empId, long contactId) {
        return jdbc.query("""
                SELECT * FROM emergency_contacts WHERE emp_id = ? AND contact_id = ? AND active_flag = 'Y'
                """, EmployeeRepository::mapContact, empId, contactId).stream().findFirst();
    }

    public void demotePrimaryContacts(long empId, String user) {
        jdbc.update("""
                UPDATE emergency_contacts SET priority_order = 2, modified_by = ?, modified_date = now()
                WHERE emp_id = ? AND priority_order = 1 AND active_flag = 'Y'
                """, user, empId);
    }

    public long insertContact(long empId, EmergencyContact c, String user) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO emergency_contacts (emp_id, contact_name, relationship, phone_primary,
                        phone_secondary, email, priority_order, active_flag, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', ?)
                    """, new String[] {"contact_id"});
            ps.setLong(1, empId);
            ps.setString(2, c.contactName());
            ps.setString(3, c.relationship());
            ps.setString(4, c.phonePrimary());
            ps.setString(5, c.phoneSecondary());
            ps.setString(6, c.email());
            ps.setInt(7, Boolean.TRUE.equals(c.isPrimary()) ? 1 : 2);
            ps.setString(8, user);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateContact(long contactId, EmergencyContact c, String user) {
        jdbc.update("""
                UPDATE emergency_contacts SET contact_name = ?, relationship = ?, phone_primary = ?,
                    phone_secondary = ?, email = ?, priority_order = ?, modified_by = ?, modified_date = now()
                WHERE contact_id = ?
                """, c.contactName(), c.relationship(), c.phonePrimary(), c.phoneSecondary(), c.email(),
                Boolean.TRUE.equals(c.isPrimary()) ? 1 : 2, user, contactId);
    }

    public void deactivateContact(long contactId, String user) {
        jdbc.update("""
                UPDATE emergency_contacts SET active_flag = 'N', modified_by = ?, modified_date = now()
                WHERE contact_id = ?
                """, user, contactId);
    }

    static EmployeeSummary toSummary(EmployeeRow r) {
        return new EmployeeSummary(r.empId(), r.empNumber(), r.firstName(), r.lastName(), r.fullName(), r.email(),
                r.deptId(), r.deptName(), r.jobId(), r.jobTitle(), r.gradeLevel(), r.managerEmpId(),
                r.locationCode(), r.hireDate(), r.employmentStatus(), r.active());
    }

    private static EmployeeRow mapRow(ResultSet rs, int n) throws SQLException {
        return new EmployeeRow(rs.getLong("emp_id"), rs.getString("emp_number"), rs.getString("first_name"),
                rs.getString("middle_name"), rs.getString("last_name"), rs.getString("email"),
                rs.getLong("dept_id"), rs.getString("dept_name"), rs.getLong("job_id"), rs.getString("job_title"),
                rs.getInt("grade_id"), rs.getObject("manager_emp_id", Long.class), rs.getString("location_code"),
                rs.getObject("location_id", Long.class), rs.getObject("hire_date", LocalDate.class),
                rs.getString("employment_status"), "Y".equals(rs.getString("active_flag")),
                rs.getObject("date_of_birth", LocalDate.class), rs.getString("gender"),
                rs.getString("marital_status"), rs.getString("phone_work"), rs.getString("phone_mobile"),
                rs.getString("address_line1"), rs.getString("address_line2"), rs.getString("city"),
                rs.getString("state_province"), rs.getString("postal_code"), rs.getString("country_code"),
                rs.getString("employment_type"), rs.getObject("termination_date", LocalDate.class),
                rs.getString("termination_reason"));
    }

    private static Dependent mapDependent(ResultSet rs, int n) throws SQLException {
        return new Dependent(rs.getLong("dependent_id"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("relationship"), rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("gender"), "Y".equals(rs.getString("benefits_enrolled")));
    }

    private static EmergencyContact mapContact(ResultSet rs, int n) throws SQLException {
        return new EmergencyContact(rs.getLong("contact_id"), rs.getString("contact_name"),
                rs.getString("relationship"), rs.getString("phone_primary"), rs.getString("phone_secondary"),
                rs.getString("email"), rs.getInt("priority_order") == 1);
    }

}
