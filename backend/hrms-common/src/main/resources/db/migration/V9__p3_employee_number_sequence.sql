-- ============================================================================
-- V9__p3_employee_number_sequence.sql - Phase 3 employee-service (contracts/p3-employee).
-- EMP_NUMBER is server-assigned from seq_emp_number (EmployeeNumberGenerator, BUG-01 /
-- PERF-04). The P0 baseline created the sequence at 1000 while the seeded employees end at
-- EMP-000099; CUTOVER_PLAN.md §7 / TEST_STRATEGY.md §7 fix the first generated number at
-- EMP-000100 (MAX + 1 restart). EmployeeRepository.nextEmpNumber() skips any number already
-- taken, so a seed above the restart value is harmless.
-- ============================================================================

alter sequence seq_emp_number start with 100 restart with 100;
