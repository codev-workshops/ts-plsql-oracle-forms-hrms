-- ============================================================================
-- V11__p3_salary_change_pct_precision.sql - Phase 3 salary-module (contracts/p3-employee).
-- SalaryRecord.changePct is the exact ROUND((new - old) / old * 100, 2) for every valid Money
-- pair (0.01..9999999999.99), never clipped or rejected. The legacy CHANGE_PCT NUMBER(5,2)
-- width overflows above 999.99; the largest valid value is 99999999999799.98 (0.01 ->
-- 9999999999.99), which NUMERIC(16,2) holds. Widening keeps the scale, so existing values are
-- preserved unchanged.
-- ============================================================================

alter table salary_records alter column change_pct type numeric(16,2);
