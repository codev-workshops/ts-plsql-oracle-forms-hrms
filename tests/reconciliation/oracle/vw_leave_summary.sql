-- Oracle side of the reconciliation pair for HRMS.VW_LEAVE_SUMMARY.
-- Ordering must match tests/reconciliation/pg/vw_leave_summary.sql.
select * from hrms.vw_leave_summary order by emp_id
