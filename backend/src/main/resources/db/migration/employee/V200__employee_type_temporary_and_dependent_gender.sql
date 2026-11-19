-- Contract v1 EmployeeCreate.employmentType allows TEMPORARY (decision 2026-11-19).
ALTER TABLE employees DROP CONSTRAINT chk_emp_type;
ALTER TABLE employees ADD CONSTRAINT chk_emp_type
    CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'TEMPORARY', 'INTERN'));

-- Contract v1 Dependent.gender (the legacy dependents block was never exported, inventory §10).
ALTER TABLE employee_dependents ADD COLUMN gender CHAR(1);
ALTER TABLE employee_dependents ADD CONSTRAINT chk_dependent_gender CHECK (gender IN ('M', 'F', 'O'));

CREATE INDEX ix_emp_last_name_upper ON employees (upper(last_name));
CREATE INDEX ix_dependents_emp ON employee_dependents (emp_id);
CREATE INDEX ix_emergency_contacts_emp ON emergency_contacts (emp_id);
