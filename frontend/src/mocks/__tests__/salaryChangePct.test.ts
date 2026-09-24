import { MOCK_DEPARTMENTS, MOCK_JOB_TITLES, MOCK_LOCATIONS } from '../handlers';
import { activeSalaryFor, insertSalary, resetEmployeeState, salaryChangePct } from '../employeeStore';

const salaryInput = (empId: number, effectiveDate: string, baseSalary: string) => ({
  empId,
  effectiveDate,
  baseSalary,
  currencyCode: 'USD',
  payFrequency: 'MONTHLY' as const,
  salaryBasis: 'ANNUAL' as const,
  changeReason: 'MERIT',
  outOfGradeBand: false,
});

describe('salaryChangePct (contracts/p3-employee: ROUND((new-old)/old*100, 2) HALF_UP)', () => {
  it('reaches the contract maximum exactly for 0.01 -> 9999999999.99', () => {
    expect(salaryChangePct('0.01', '9999999999.99')).toBe('99999999999800.00');
  });

  it('computes the normal case 66000.00 -> 999999.00', () => {
    expect(salaryChangePct('66000.00', '999999.00')).toBe('1415.15');
  });

  it('does not double-round 9998000000.01 -> 9998499900.01', () => {
    expect(salaryChangePct('9998000000.01', '9998499900.01')).toBe('0.00');
  });

  it('rounds HALF_UP away from zero on both signs and keeps two decimals', () => {
    expect(salaryChangePct('52000.00', '58000.00')).toBe('11.54');
    expect(salaryChangePct('100000.00', '100005.00')).toBe('0.01');
    expect(salaryChangePct('100000.00', '99995.00')).toBe('-0.01');
    expect(salaryChangePct('100000.00', '50000.00')).toBe('-50.00');
    expect(salaryChangePct('100000.00', '100000.00')).toBe('0.00');
  });

  it('returns null when the prior salary is unusable', () => {
    expect(salaryChangePct('0.00', '1000.00')).toBeNull();
    expect(salaryChangePct('abc', '1000.00')).toBeNull();
    expect(salaryChangePct('1000', '1000.00')).toBeNull();
  });
});

describe('insertSalary', () => {
  beforeEach(() => resetEmployeeState({ departments: MOCK_DEPARTMENTS, jobTitles: MOCK_JOB_TITLES, locations: MOCK_LOCATIONS }));

  it('returns null changePct for a first salary and closes the prior record on subsequent ones', () => {
    const first = insertSalary(salaryInput(999, '2025-01-01', '0.01'), 'tester');
    expect(first.changePct).toBeNull();
    expect(first.active).toBe(true);

    const second = insertSalary(salaryInput(999, '2025-02-01', '9999999999.99'), 'tester');
    expect(second.changePct).toBe('99999999999800.00');
    expect(first.active).toBe(false);
    expect(first.endDate).toBe('2025-02-01');
    expect(activeSalaryFor(999)).toBe(second);
  });

  it('uses the seeded active salary as the prior record', () => {
    const record = insertSalary(salaryInput(11, '2025-01-01', '66000.00'), 'tester');
    expect(record.changePct).toBe('13.79');
  });
});
