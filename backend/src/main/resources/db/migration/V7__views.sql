-- ============================================================================
-- P1: views (schema/views/hrms_views.sql)
-- SYSDATE -> CURRENT_DATE (business-date comparisons), CHAR(1) 'Y' -> boolean,
-- CONNECT BY -> recursive CTE. Column names and semantics are kept for parity;
-- deviations are listed in docs/modernization/checkpoint.md (P1 ledger notes).
-- ============================================================================

-- VW_ACTIVE_EMPLOYEES (VW-001)
-- TENURE_YEARS: Oracle TRUNC(MONTHS_BETWEEN(SYSDATE, HIRE_DATE) / 12, 1); MONTHS_BETWEEN counts
-- the remaining days as days/31.
CREATE VIEW hrms.vw_active_employees AS
SELECT e.emp_id, e.emp_number, e.first_name, e.last_name,
       e.first_name || ' ' || e.last_name AS full_name,
       e.email, e.phone_work, e.phone_mobile,
       e.hire_date,
       trunc(((date_part('year', age(CURRENT_DATE, e.hire_date)) * 12
               + date_part('month', age(CURRENT_DATE, e.hire_date))
               + date_part('day', age(CURRENT_DATE, e.hire_date)) / 31.0) / 12)::numeric, 1) AS tenure_years,
       e.employment_type, e.employment_status,
       e.dept_id, d.dept_name, d.dept_code, d.cost_center,
       e.job_id, j.job_title, j.job_code,
       g.grade_id, g.grade_name,
       e.manager_emp_id,
       m.first_name || ' ' || m.last_name AS manager_name,
       e.location_code,
       l.location_name, l.city, l.state_province, l.country_code,
       sr.base_salary AS current_salary,
       sr.currency_code, sr.pay_frequency
FROM hrms.employees e
JOIN hrms.departments d ON e.dept_id = d.dept_id
JOIN hrms.job_titles j ON e.job_id = j.job_id
JOIN hrms.job_grades g ON j.grade_id = g.grade_id
LEFT JOIN hrms.employees m ON e.manager_emp_id = m.emp_id
LEFT JOIN hrms.locations l ON e.location_code = l.location_code
LEFT JOIN hrms.salary_records sr ON e.emp_id = sr.emp_id
    AND sr.active_flag
    AND sr.effective_date <= CURRENT_DATE
    AND (sr.end_date IS NULL OR sr.end_date > CURRENT_DATE)
WHERE e.employment_status = 'ACTIVE'
AND e.active_flag;

COMMENT ON VIEW hrms.vw_active_employees IS
    'Denormalized view of active employees with department, job, manager, location, and salary';

-- VW_ORG_HIERARCHY (VW-002)
-- Oracle semantics kept: the hierarchy is built over ALL employees from the roots
-- (MANAGER_EMP_ID IS NULL), and the ACTIVE filter is applied afterwards (Oracle evaluates
-- WHERE after CONNECT BY). ORG_PATH matches SYS_CONNECT_BY_PATH(name, ' > ') (leading
-- separator). IS_LEAF matches CONNECT_BY_ISLEAF (computed before the filter), as boolean.
-- ORDER SIBLINGS BY LAST_NAME -> ORDER BY a per-level sort path. CYCLE guards against
-- manager loops (Oracle raised ORA-01436).
CREATE VIEW hrms.vw_org_hierarchy AS
WITH RECURSIVE tree AS (
    SELECT e.emp_id, e.emp_number, e.first_name, e.last_name, e.manager_emp_id, e.dept_id,
           e.employment_status,
           1 AS org_level,
           ' > ' || e.first_name || ' ' || e.last_name AS org_path,
           ARRAY[(rpad(e.last_name, 50) || lpad(e.emp_id::text, 19, '0')) COLLATE "C"] AS sort_path
    FROM hrms.employees e
    WHERE e.manager_emp_id IS NULL
    UNION ALL
    SELECT c.emp_id, c.emp_number, c.first_name, c.last_name, c.manager_emp_id, c.dept_id,
           c.employment_status,
           t.org_level + 1,
           t.org_path || ' > ' || c.first_name || ' ' || c.last_name,
           t.sort_path || ((rpad(c.last_name, 50) || lpad(c.emp_id::text, 19, '0')) COLLATE "C")
    FROM hrms.employees c
    JOIN tree t ON c.manager_emp_id = t.emp_id
) CYCLE emp_id SET is_cycle USING cycle_path
SELECT t.emp_id, t.emp_number, t.first_name || ' ' || t.last_name AS emp_name,
       t.manager_emp_id, t.dept_id,
       t.org_level,
       t.org_path,
       NOT EXISTS (SELECT 1 FROM hrms.employees r WHERE r.manager_emp_id = t.emp_id) AS is_leaf
FROM tree t
WHERE NOT t.is_cycle
AND t.employment_status = 'ACTIVE'
ORDER BY t.sort_path;

-- VW_EMPLOYEE_COMPENSATION (VW-003)
CREATE VIEW hrms.vw_employee_compensation AS
SELECT e.emp_id, e.emp_number,
       e.first_name || ' ' || e.last_name AS emp_name,
       d.dept_name, j.job_title, g.grade_name,
       sr.base_salary,
       g.min_salary AS grade_min,
       g.max_salary AS grade_max,
       (g.min_salary + g.max_salary) / 2 AS grade_midpoint,
       round(sr.base_salary / ((g.min_salary + g.max_salary) / 2) * 100, 1) AS compa_ratio,
       sr.effective_date AS salary_effective_date,
       sr.change_reason AS last_change_reason,
       sr.change_pct AS last_change_pct
FROM hrms.employees e
JOIN hrms.departments d ON e.dept_id = d.dept_id
JOIN hrms.job_titles j ON e.job_id = j.job_id
JOIN hrms.job_grades g ON j.grade_id = g.grade_id
JOIN hrms.salary_records sr ON e.emp_id = sr.emp_id AND sr.active_flag
WHERE e.employment_status = 'ACTIVE';

-- VW_LEAVE_SUMMARY (VW-004)
-- Parity: AVAILABLE here excludes PENDING, unlike leave_balances.available (DEF-23).
CREATE VIEW hrms.vw_leave_summary AS
SELECT e.emp_id, e.emp_number,
       e.first_name || ' ' || e.last_name AS emp_name,
       d.dept_name,
       lt.leave_type_name,
       lb.opening_balance,
       lb.accrued,
       lb.used,
       lb.adjustment,
       lb.pending,
       lb.opening_balance + lb.accrued - lb.used + lb.adjustment AS available,
       round(lb.used * 100 / NULLIF(lb.opening_balance + lb.accrued, 0), 1) AS utilization_pct
FROM hrms.leave_balances lb
JOIN hrms.employees e ON lb.emp_id = e.emp_id
JOIN hrms.departments d ON e.dept_id = d.dept_id
JOIN hrms.leave_types lt ON lb.leave_type_id = lt.leave_type_id
WHERE lb.calendar_year = EXTRACT(YEAR FROM CURRENT_DATE)
AND e.employment_status = 'ACTIVE';

-- VW_PAYROLL_LATEST (VW-005)
CREATE VIEW hrms.vw_payroll_latest AS
SELECT pd.emp_id, e.emp_number,
       e.first_name || ' ' || e.last_name AS emp_name,
       pp.period_name,
       sum(CASE WHEN pd.element_type = 'EARNING' THEN pd.amount ELSE 0 END) AS gross_pay,
       sum(CASE WHEN pd.element_type = 'TAX' THEN abs(pd.amount) ELSE 0 END) AS total_taxes,
       sum(CASE WHEN pd.element_type IN ('DEDUCTION', 'BENEFIT') THEN abs(pd.amount) ELSE 0 END) AS total_deductions,
       sum(pd.amount) AS net_pay
FROM hrms.payroll_details pd
JOIN hrms.employees e ON pd.emp_id = e.emp_id
JOIN hrms.payroll_runs pr ON pd.run_id = pr.run_id
JOIN hrms.pay_periods pp ON pr.period_id = pp.period_id
WHERE pr.run_id = (
    SELECT max(pr2.run_id)
    FROM hrms.payroll_runs pr2
    WHERE pr2.status = 'APPROVED'
)
AND pd.status <> 'ERROR'
GROUP BY pd.emp_id, e.emp_number,
         e.first_name || ' ' || e.last_name,
         pp.period_name;

-- VW_PENDING_APPROVALS (VW-006)
-- Oracle NUMBER-to-text drops trailing zeros (3 -> '3'); trim_scale reproduces that
-- (Oracle also drops the leading zero: 0.5 -> '.5'; PostgreSQL gives '0.5').
CREATE VIEW hrms.vw_pending_approvals AS
SELECT 'LEAVE'::varchar(20) AS approval_type,
       lr.request_id AS item_id,
       lr.approver_emp_id AS approver_id,
       e.first_name || ' ' || e.last_name AS requestor_name,
       lt.leave_type_name::varchar(200) AS item_description,
       lr.created_date AS request_date,
       (trim_scale(lr.total_days)::text || ' day(s) ' ||
           to_char(lr.start_date, 'MM/DD') || '-' || to_char(lr.end_date, 'MM/DD'))::varchar(200) AS details
FROM hrms.leave_requests lr
JOIN hrms.employees e ON lr.emp_id = e.emp_id
JOIN hrms.leave_types lt ON lr.leave_type_id = lt.leave_type_id
WHERE lr.status = 'PENDING'
UNION ALL
SELECT 'PERFORMANCE'::varchar(20) AS approval_type,
       pr.review_id AS item_id,
       pr.reviewer_emp_id AS approver_id,
       e.first_name || ' ' || e.last_name AS requestor_name,
       ('Performance Review - ' || rc.cycle_name)::varchar(200) AS item_description,
       pr.created_date AS request_date,
       pr.status::varchar(200) AS details
FROM hrms.performance_reviews pr
JOIN hrms.employees e ON pr.emp_id = e.emp_id
JOIN hrms.review_cycles rc ON pr.cycle_id = rc.cycle_id
WHERE pr.status = 'MANAGER_REVIEW';
