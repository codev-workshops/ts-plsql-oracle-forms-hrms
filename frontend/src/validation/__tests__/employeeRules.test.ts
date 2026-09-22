import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { evaluateCustomRule, fieldErrors, getDto, zodFor } from '../schema';

function iso(offsetDays: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

describe('P3 employee custom rules from validation-schema.json', () => {
  it('employee.hireDateLimit uses the exported HR.MAX_FUTURE_HIRE_DAYS operand and reports -20501', () => {
    const [rule] = getDto('EmployeeCreateRequest').fields.hireDate.rules!;
    expect(rule.errorCode).toBe('-20501');
    expect(evaluateCustomRule(rule, iso(90), {})).toBe(true);
    expect(evaluateCustomRule(rule, iso(91), {})).toBe(false);
    expect(evaluateCustomRule(rule, iso(-3650), {})).toBe(true);
    const parsed = zodFor('EmployeeCreateRequest').safeParse({ firstName: 'A', lastName: 'B', hireDate: iso(91), deptId: 1, jobId: 1 });
    expect(parsed.success).toBe(false);
    if (!parsed.success) expect(fieldErrors(parsed.error)).toEqual({ hireDate: 'Hire date cannot be more than 90 days in the future' });
  });

  it('employee.dateNotFuture rejects a future date of birth', () => {
    const [rule] = getDto('EmployeeCreateRequest').fields.dateOfBirth.rules!;
    expect(evaluateCustomRule(rule, iso(0), {})).toBe(true);
    expect(evaluateCustomRule(rule, iso(1), {})).toBe(false);
    const parsed = zodFor('EmployeeCreateRequest').safeParse({ firstName: 'A', lastName: 'B', hireDate: iso(0), deptId: 1, jobId: 1, dateOfBirth: iso(1) });
    expect(parsed.success).toBe(false);
    if (!parsed.success) expect(fieldErrors(parsed.error)).toEqual({ dateOfBirth: 'Date of birth cannot be in the future' });
  });

  it('EmployeeCreateRequest: required names, SSN pattern, salary minimum (-20101 rule) and integer refs', () => {
    const schema = zodFor('EmployeeCreateRequest');
    const bad = schema.safeParse({ firstName: '', lastName: ' ', hireDate: iso(1), deptId: '1', jobId: 'x', ssn: '12-34', initialSalary: '0', email: 'nope' });
    expect(bad.success).toBe(false);
    if (!bad.success) {
      const errors = fieldErrors(bad.error);
      expect(errors.firstName).toBe('First name is required');
      expect(errors.lastName).toBe('Last name is required');
      expect(errors.ssn).toBe('SSN must be 9 digits (NNN-NN-NNNN)');
      expect(errors.initialSalary).toBe('Salary must be positive');
      expect(errors.email).toBe('Enter a valid e-mail address');
      expect(errors.jobId).toBeDefined();
    }
    const ok = schema.safeParse({ firstName: ' Ada ', lastName: 'Lovelace', hireDate: iso(10), deptId: '1', jobId: '1', email: SEED_ACCOUNTS.staff.email, initialSalary: '45000' });
    expect(ok.success).toBe(true);
    if (ok.success) expect(ok.data).toMatchObject({ firstName: 'Ada', deptId: 1, jobId: 1, initialSalary: 45000 });
  });

  it('EmployeeUpdateRequest does not accept the server-owned fields', () => {
    const fields = Object.keys(getDto('EmployeeUpdateRequest').fields);
    expect(fields).not.toContain('empNumber');
    expect(fields).not.toContain('employmentStatus');
    expect(fields).not.toContain('hireDate');
    expect(fields).not.toContain('deptId');
  });

  it('SalaryChangeRequest: baseSalary minimum carries the -20101 message, enums come from the JSON', () => {
    const schema = zodFor('SalaryChangeRequest');
    const bad = schema.safeParse({ effectiveDate: iso(0), baseSalary: '-5', changeReason: 'MERIT', payFrequency: 'DAILY' });
    expect(bad.success).toBe(false);
    if (!bad.success) {
      const errors = fieldErrors(bad.error);
      expect(errors.baseSalary).toBe('Salary must be positive');
      expect(errors.payFrequency).toBeDefined();
    }
    expect(getDto('SalaryChangeRequest').fields.payFrequency.values).toEqual(['WEEKLY', 'BIWEEKLY', 'SEMIMONTHLY', 'MONTHLY']);
  });
});
