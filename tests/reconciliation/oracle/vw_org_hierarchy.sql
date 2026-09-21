-- Oracle side of the reconciliation pair for HRMS.VW_ORG_HIERARCHY.
-- Ordering must match tests/reconciliation/pg/vw_org_hierarchy.sql.
select * from hrms.vw_org_hierarchy -- CONNECT BY ... ORDER SIBLINGS BY already fixes the row order; do not re-sort.
