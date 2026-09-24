-- Oracle side of the reconciliation pair for HRMS.VW_EMPLOYEE_COMPENSATION.
-- Ordering must match tests/reconciliation/pg/vw_employee_compensation.sql.
select * from hrms.vw_employee_compensation order by emp_id
