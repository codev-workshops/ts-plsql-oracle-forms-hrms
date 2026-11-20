-- DEV/TEST ONLY (repeatable; classpath:db/seed is loaded only with the 'dev' profile and in tests,
-- never by the production migration path). P3 / OQ-15 provisional default:
--   * every seeded ACTIVE employee can sign in with their work email and the shared dev password
--     'Hrms#2026' (documented in backend/README.md). Production credentials come from the P8
--     data migration (unusable hash + one-time reset link), never from this file.
--   * current-year balance rows for every active leave type (PTO 10, SICK 5 opening days) so the pilot is usable
--     before the first monthly accrual run.
INSERT INTO hrms.user_credentials (emp_id, password_hash, created_by)
SELECT e.emp_id, '{bcrypt}$2a$10$z2vfTPFVyquJzecilpS6T.CtoEYCNICqZlWTKMYI0z9KP5C2Z.J1.', 'system:seed'
FROM hrms.employees e
WHERE e.employment_status = 'ACTIVE' AND e.active_flag
ON CONFLICT (emp_id) DO NOTHING;

INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, opening_balance, created_by)
SELECT e.emp_id, t.leave_type_id, EXTRACT(YEAR FROM CURRENT_DATE)::int,
       CASE t.leave_type_code WHEN 'PTO' THEN 10 WHEN 'SICK' THEN 5 ELSE 0 END, 'system:seed'
FROM hrms.employees e CROSS JOIN hrms.leave_types t
WHERE e.employment_status = 'ACTIVE' AND e.active_flag AND t.active_flag
ON CONFLICT (emp_id, leave_type_id, calendar_year) DO NOTHING;
