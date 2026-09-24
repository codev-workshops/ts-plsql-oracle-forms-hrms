-- ============================================================================
-- V10__p3_employee_history_reason_code.sql - Phase 3 employee-service (contracts/p3-employee).
-- changeSalary writes employee_history.reason_code = SalaryChangeRequest.changeReason, whose
-- contract maxLength is 50 while the legacy column was VARCHAR2(30). Widen the PostgreSQL
-- column so the frozen server step stores the reason verbatim (no truncation, no error).
-- ============================================================================

alter table employee_history alter column reason_code type varchar(50);
