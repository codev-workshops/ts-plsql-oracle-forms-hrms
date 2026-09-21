-- Oracle side of the reconciliation pair for HRMS.VW_PENDING_APPROVALS.
-- Ordering must match tests/reconciliation/pg/vw_pending_approvals.sql.
select * from hrms.vw_pending_approvals order by approval_type, item_id
