package com.hrms.reporting;

import com.hrms.common.ApiException;
import com.hrms.common.ErrorCode;
import com.hrms.reporting.ReportModels.EmployeeCompensation;
import com.hrms.reporting.ReportModels.LeaveSummaryRow;
import com.hrms.reporting.ReportModels.OrgNode;
import com.hrms.reporting.ReportModels.PayrollLatest;
import com.hrms.reporting.ReportModels.PendingApproval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only reports over the P1 views (V7). Views that depend on "today" read the DB clock, so the leave
 * summary queries the tables with the year from the injected clock instead (D-04, P1 note).
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    public static final int DEFAULT_ORG_DEPTH = 10;

    private final JdbcClient jdbc;
    private final Clock clock;

    public ReportService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<PendingApproval> pendingApprovals(Long approverId, String approvalType) {
        return jdbc.sql("""
                SELECT approval_type, item_id, approver_id, requestor_name, item_description, request_date, details
                FROM hrms.vw_pending_approvals
                WHERE (CAST(:approver AS bigint) IS NULL OR approver_id = :approver)
                  AND (CAST(:type AS varchar) IS NULL OR approval_type = :type)
                ORDER BY request_date, approval_type, item_id
                """).param("approver", approverId).param("type", approvalType)
                .query((rs, n) -> new PendingApproval(rs.getString(1), rs.getLong(2), rs.getObject(3, Long.class),
                        rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getString(7)))
                .list();
    }

    public List<EmployeeCompensation> employeeCompensation() {
        return jdbc.sql("""
                SELECT emp_id, emp_number, emp_name, dept_name, job_title, grade_name, base_salary, grade_min,
                       grade_max, grade_midpoint, compa_ratio, salary_effective_date, last_change_reason,
                       last_change_pct
                FROM hrms.vw_employee_compensation
                ORDER BY dept_name, emp_name, emp_id
                """).query((rs, n) -> new EmployeeCompensation(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11),
                        rs.getObject(12, LocalDate.class), rs.getString(13), rs.getBigDecimal(14)))
                .list();
    }

    /** Same rows and formulas as hrms.vw_leave_summary, for {@code year} (default: the clock's year). */
    public List<LeaveSummaryRow> leaveSummary(Integer year, Long empId, Long deptId) {
        int y = year != null ? year : LocalDate.now(clock).getYear();
        return jdbc.sql("""
                SELECT e.emp_id, e.emp_number, e.first_name || ' ' || e.last_name, d.dept_name, lt.leave_type_name,
                       lb.calendar_year, lb.opening_balance, lb.accrued, lb.used, lb.adjustment, lb.pending,
                       lb.available,
                       lb.opening_balance + lb.accrued - lb.used + lb.adjustment,
                       round(lb.used * 100 / NULLIF(lb.opening_balance + lb.accrued, 0), 1)
                FROM hrms.leave_balances lb
                JOIN hrms.employees e ON lb.emp_id = e.emp_id
                JOIN hrms.departments d ON e.dept_id = d.dept_id
                JOIN hrms.leave_types lt ON lb.leave_type_id = lt.leave_type_id
                WHERE lb.calendar_year = :year
                  AND e.employment_status = 'ACTIVE'
                  AND (CAST(:emp AS bigint) IS NULL OR e.emp_id = :emp)
                  AND (CAST(:dept AS bigint) IS NULL OR e.dept_id = :dept)
                ORDER BY d.dept_name, e.last_name, e.first_name, e.emp_id, lt.leave_type_name
                """).param("year", y).param("emp", empId).param("dept", deptId)
                .query((rs, n) -> new LeaveSummaryRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12),
                        rs.getBigDecimal(13), rs.getBigDecimal(14)))
                .list();
    }

    /** Whole organization (VW_ORG_HIERARCHY) or, with a root, PKG_EMPLOYEE.get_org_chart semantics. */
    public List<OrgNode> orgHierarchy(Long rootEmpId, Integer maxDepth) {
        if (rootEmpId == null) {
            if (maxDepth != null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "maxDepth requires rootEmpId");
            }
            return jdbc.sql("""
                    SELECT emp_id, emp_number, emp_name, manager_emp_id, dept_id, org_level, org_path, is_leaf
                    FROM hrms.vw_org_hierarchy
                    """).query(ReportService::orgNode).list();
        }
        int depth = maxDepth != null ? maxDepth : DEFAULT_ORG_DEPTH;
        if (depth < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "maxDepth must be at least 1");
        }
        boolean exists = jdbc.sql("SELECT count(*) FROM hrms.employees WHERE emp_id = :id")
                .param("id", rootEmpId).query(Long.class).single() > 0;
        if (!exists) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found", -20001);
        }
        // Oracle START WITH root CONNECT BY ... AND LEVEL <= depth: the walk passes through inactive
        // employees; WHERE employment_status = 'ACTIVE' filters afterwards. ORDER SIBLINGS BY last, first name.
        return jdbc.sql("""
                WITH RECURSIVE tree AS (
                    SELECT e.emp_id, e.emp_number, e.first_name, e.last_name, e.manager_emp_id, e.dept_id,
                           e.employment_status, 1 AS org_level,
                           ' > ' || e.first_name || ' ' || e.last_name AS org_path,
                           ARRAY[(rpad(e.last_name, 50) || rpad(e.first_name, 50)
                                  || lpad(e.emp_id::text, 19, '0')) COLLATE "C"] AS sort_path
                    FROM hrms.employees e
                    WHERE e.emp_id = :root
                    UNION ALL
                    SELECT c.emp_id, c.emp_number, c.first_name, c.last_name, c.manager_emp_id, c.dept_id,
                           c.employment_status, t.org_level + 1,
                           t.org_path || ' > ' || c.first_name || ' ' || c.last_name,
                           t.sort_path || ((rpad(c.last_name, 50) || rpad(c.first_name, 50)
                                  || lpad(c.emp_id::text, 19, '0')) COLLATE "C")
                    FROM hrms.employees c
                    JOIN tree t ON c.manager_emp_id = t.emp_id
                    WHERE t.org_level < :depth
                ) CYCLE emp_id SET is_cycle USING cycle_path
                SELECT t.emp_id, t.emp_number, t.first_name || ' ' || t.last_name, t.manager_emp_id, t.dept_id,
                       t.org_level, t.org_path,
                       NOT EXISTS (SELECT 1 FROM hrms.employees r WHERE r.manager_emp_id = t.emp_id)
                FROM tree t
                WHERE NOT t.is_cycle AND t.employment_status = 'ACTIVE'
                ORDER BY t.sort_path
                """).param("root", rootEmpId).param("depth", depth).query(ReportService::orgNode).list();
    }

    public List<PayrollLatest> payrollLatest() {
        return jdbc.sql("""
                SELECT emp_id, emp_number, emp_name, period_name, gross_pay, total_taxes, total_deductions, net_pay
                FROM hrms.vw_payroll_latest
                ORDER BY emp_name, emp_id
                """).query((rs, n) -> new PayrollLatest(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getBigDecimal(8)))
                .list();
    }

    private static OrgNode orgNode(ResultSet rs, int n) throws SQLException {
        return new OrgNode(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, Long.class),
                rs.getLong(5), rs.getInt(6), rs.getString(7), rs.getBoolean(8));
    }
}
