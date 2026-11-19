-- Accept the v1 run types while keeping existing legacy run types readable.
ALTER TABLE payroll_runs DROP CONSTRAINT chk_run_type;
ALTER TABLE payroll_runs ADD CONSTRAINT chk_run_type
    CHECK (run_type IN ('REGULAR', 'SUPPLEMENTAL', 'OFF_CYCLE', 'BONUS', 'FINAL', 'CORRECTION'));

CREATE UNIQUE INDEX ux_pay_period_frequency_name ON pay_periods (pay_frequency, period_name);
CREATE INDEX ix_payroll_details_employee_ytd ON payroll_details (emp_id, run_id)
    WHERE status = 'CALCULATED';

ALTER TABLE payroll_runs ADD COLUMN calculated_at TIMESTAMP;
