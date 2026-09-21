-- Oracle side of the reconciliation pair for HRMS.VW_ACTIVE_EMPLOYEES.
-- Ordering must match tests/reconciliation/pg/vw_active_employees.sql.
select * from hrms.vw_active_employees order by emp_id
