-- Oracle side of the reconciliation pair for HRMS.VW_PAYROLL_LATEST.
-- Ordering must match tests/reconciliation/pg/vw_payroll_latest.sql.
select * from hrms.vw_payroll_latest order by emp_id
