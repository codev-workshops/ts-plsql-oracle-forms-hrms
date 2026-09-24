import type {
  DepartmentRef,
  Dependent,
  EmergencyContact,
  EmployeeDetail,
  EmployeeHistoryEntry,
  EmployeeListItem,
  EmployeeSummary,
  JobTitleRef,
  LocationRef,
  SalaryRecord,
} from '../api/types';

/**
 * In-memory `employee-service` + `salary-module` state for the msw contract mocks
 * (contracts/p3-employee/openapi.yaml). Employee ids/names mirror e2e/seed-accounts.ts and
 * mocks/performanceStore.ts so the P1/P2 mocks keep resolving the same people.
 */

export interface EmployeeRow extends EmployeeDetail {
  /** Stored only to serve `ssnLast4`; never returned whole (SEC-01). */
  ssn: string | null;
}

interface Refs {
  departments: DepartmentRef[];
  jobTitles: JobTitleRef[];
  locations: LocationRef[];
}

let refs: Refs = { departments: [], jobTitles: [], locations: [] };
let employees: EmployeeRow[] = [];
let salaries: SalaryRecord[] = [];
let history: EmployeeHistoryEntry[] = [];
let dependents: Dependent[] = [];
let contacts: EmergencyContact[] = [];
let nextEmpId = 100;
let nextEmpNumber = 100;
let nextSalaryId = 500;
let nextHistId = 900;
let nextDependentId = 50;
let nextContactId = 70;

const NOW = '2025-01-15T00:00:00Z';

export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

export function departmentById(id: number) {
  return refs.departments.find((d) => d.deptId === id);
}
export function jobById(id: number) {
  return refs.jobTitles.find((j) => j.jobId === id);
}
export function locationByCode(code: string | null) {
  return code ? refs.locations.find((l) => l.locationCode === code) : undefined;
}

function seedEmployee(
  id: number,
  firstName: string,
  lastName: string,
  deptId: number,
  jobId: number,
  managerEmpId: number | null,
  locationCode: string,
  hireDate: string,
  extra: Partial<EmployeeRow> = {},
): EmployeeRow {
  const dept = departmentById(deptId)!;
  const job = jobById(jobId)!;
  const manager = managerEmpId ? employees.find((e) => e.id === managerEmpId) : undefined;
  return {
    id,
    empNumber: `EMP-${String(id).padStart(6, '0')}`,
    firstName,
    middleName: null,
    lastName,
    dateOfBirth: '1985-06-15',
    gender: 'F',
    maritalStatus: null,
    nationality: null,
    ssn: '123-45-6789',
    ssnLast4: '6789',
    email: `${firstName.toLowerCase()}.${lastName.toLowerCase()}@company.com`,
    phoneWork: '212-555-0100',
    phoneMobile: null,
    addressLine1: null,
    addressLine2: null,
    city: null,
    stateProvince: null,
    postalCode: null,
    countryCode: 'USA',
    hireDate,
    terminationDate: null,
    terminationReason: null,
    deptId,
    deptName: dept.deptName,
    jobId,
    jobTitle: job.jobTitle,
    gradeCode: job.gradeCode,
    managerEmpId,
    managerName: manager ? `${manager.firstName} ${manager.lastName}` : null,
    locationCode,
    locationName: locationByCode(locationCode)?.locationName ?? null,
    employmentType: 'FULL_TIME',
    employmentStatus: 'ACTIVE',
    active: true,
    notes: null,
    version: 0,
    createdBy: 'system',
    createdDate: NOW,
    modifiedBy: null,
    modifiedDate: null,
    ...extra,
  };
}

function seedSalary(salaryId: number, empId: number, effectiveDate: string, endDate: string | null, baseSalary: string, changeReason: string | null, changePct: string | null, outOfGradeBand = false): SalaryRecord {
  return {
    salaryId,
    empId,
    effectiveDate,
    endDate,
    baseSalary,
    currencyCode: 'USD',
    payFrequency: 'MONTHLY',
    salaryBasis: 'ANNUAL',
    changeReason,
    changePct,
    active: endDate === null,
    outOfGradeBand,
    createdBy: 'system',
    createdDate: NOW,
  };
}

function seedHistory(histId: number, empId: number, changeType: EmployeeHistoryEntry['changeType'], effectiveDate: string, extra: Partial<EmployeeHistoryEntry> = {}): EmployeeHistoryEntry {
  return {
    histId,
    empId,
    changeType,
    effectiveDate,
    oldDeptId: null,
    oldDeptName: null,
    newDeptId: null,
    newDeptName: null,
    oldJobId: null,
    oldJobTitle: null,
    newJobId: null,
    newJobTitle: null,
    oldManagerId: null,
    oldManagerName: null,
    newManagerId: null,
    newManagerName: null,
    oldSalary: null,
    newSalary: null,
    oldLocation: null,
    newLocation: null,
    reasonCode: null,
    comments: null,
    createdBy: 'system',
    createdDate: NOW,
    ...extra,
  };
}

export function resetEmployeeState(next: Refs) {
  refs = next;
  employees = [];
  employees.push(seedEmployee(1, 'JAMES', 'RICHARDSON', 1, 4, null, 'HQ', '2010-01-04', { gender: 'M' }));
  employees.push(seedEmployee(21, 'JENNIFER', 'PARK', 3, 3, 1, 'SF', '2015-03-02'));
  employees.push(seedEmployee(11, 'DAVID', 'MARTINEZ', 3, 1, 21, 'SF', '2019-06-10', { gender: 'M', phoneMobile: '415-555-0199' }));
  employees.push(seedEmployee(12, 'EMILY', 'JOHNSON', 2, 1, 21, 'HQ', '2022-09-12'));
  employees.push(seedEmployee(13, 'SARAH', 'LEE', 3, 1, 21, 'SF', '2021-01-18'));
  employees.push(seedEmployee(4, 'MIA', 'MANAGER', 1, 3, 1, 'HQ', '2012-05-21'));
  employees.push(
    seedEmployee(9, 'ROBERT', 'OLDMAN', 2, 1, 21, 'CHI', '2005-02-01', {
      gender: 'M',
      employmentStatus: 'TERMINATED',
      active: false,
      terminationDate: '2023-12-31',
      terminationReason: 'RETIRED',
    }),
  );
  salaries = [
    seedSalary(1, 1, '2010-01-04', null, '350000.00', 'HIRE', null, true),
    seedSalary(2, 21, '2015-03-02', null, '110000.00', 'HIRE', null),
    seedSalary(3, 11, '2019-06-10', '2023-12-31', '52000.00', 'HIRE', null),
    seedSalary(4, 11, '2024-01-01', null, '58000.00', 'MERIT', '11.54'),
    seedSalary(5, 12, '2022-09-12', null, '48000.00', 'HIRE', null),
    seedSalary(6, 13, '2021-01-18', null, '55000.00', 'HIRE', null),
    seedSalary(7, 4, '2012-05-21', null, '95000.00', 'HIRE', null),
    seedSalary(8, 9, '2005-02-01', '2023-12-31', '60000.00', 'HIRE', null),
  ];
  history = [
    seedHistory(1, 11, 'HIRE', '2019-06-10', { newDeptId: 2, newDeptName: 'Finance', newJobId: 1, newJobTitle: 'Analyst', newSalary: '52000.00' }),
    seedHistory(2, 11, 'TRANSFER', '2021-04-01', { oldDeptId: 2, oldDeptName: 'Finance', newDeptId: 3, newDeptName: 'Engineering', reasonCode: 'REORG' }),
    seedHistory(3, 11, 'SALARY_CHANGE', '2024-01-01', { oldSalary: '52000.00', newSalary: '58000.00', reasonCode: 'MERIT' }),
    seedHistory(4, 9, 'TERMINATION', '2023-12-31', { reasonCode: 'RETIRED' }),
  ];
  dependents = [
    { dependentId: 1, empId: 11, firstName: 'LUCIA', lastName: 'MARTINEZ', relationship: 'SPOUSE', dateOfBirth: '1986-02-20', ssnLast4: '4321', benefitsEnrolled: true, active: true },
    { dependentId: 2, empId: 11, firstName: 'MATEO', lastName: 'MARTINEZ', relationship: 'CHILD', dateOfBirth: '2015-08-09', ssnLast4: null, benefitsEnrolled: true, active: true },
    { dependentId: 3, empId: 11, firstName: 'OLD', lastName: 'ENTRY', relationship: 'OTHER', dateOfBirth: null, ssnLast4: null, benefitsEnrolled: false, active: false },
  ];
  contacts = [
    { contactId: 1, empId: 11, contactName: 'LUCIA MARTINEZ', relationship: 'Spouse', phonePrimary: '415-555-0101', phoneSecondary: null, email: 'lucia@example.com', priorityOrder: 1, active: true },
    { contactId: 2, empId: 11, contactName: 'CARLOS MARTINEZ', relationship: 'Brother', phonePrimary: '415-555-0102', phoneSecondary: null, email: null, priorityOrder: 2, active: true },
  ];
  nextEmpId = 100;
  nextEmpNumber = 100;
  nextSalaryId = 500;
  nextHistId = 900;
  nextDependentId = 50;
  nextContactId = 70;
}

export function getEmployeeRow(id: number) {
  return employees.find((e) => e.id === id);
}

export function listEmployeeRows() {
  return employees;
}

/** `ssn` never leaves the store (SEC-01, contract "SSN never echoed"). */
export function toDetail(row: EmployeeRow, includeSsnLast4: boolean): EmployeeDetail {
  const { ssn: _ssn, ...detail } = row;
  void _ssn;
  return clone({ ...detail, ssnLast4: includeSsnLast4 ? detail.ssnLast4 : null });
}

export function toListItem(row: EmployeeRow): EmployeeListItem {
  return {
    id: row.id,
    empNumber: row.empNumber,
    firstName: row.firstName,
    lastName: row.lastName,
    email: row.email,
    deptId: row.deptId,
    deptName: row.deptName,
    jobId: row.jobId,
    jobTitle: row.jobTitle,
    managerEmpId: row.managerEmpId,
    managerName: row.managerName,
    locationCode: row.locationCode,
    hireDate: row.hireDate,
    employmentType: row.employmentType,
    employmentStatus: row.employmentStatus,
    active: row.active,
  };
}

export function toSummary(row: EmployeeRow): EmployeeSummary {
  return { id: row.id, empNumber: row.empNumber, name: `${row.firstName} ${row.lastName}`, jobTitle: row.jobTitle };
}

export function insertEmployee(row: Omit<EmployeeRow, 'id' | 'empNumber'>): EmployeeRow {
  const id = nextEmpId++;
  const created: EmployeeRow = { ...row, id, empNumber: `EMP-${String(nextEmpNumber++).padStart(6, '0')}` };
  employees.push(created);
  return created;
}

export function activeSalaryFor(empId: number) {
  return salaries.find((s) => s.empId === empId && s.active);
}

export function salaryHistoryFor(empId: number) {
  return salaries.filter((s) => s.empId === empId).sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate) || b.salaryId - a.salaryId);
}

const MONEY_PATTERN = /^-?\d+\.\d{2}$/;

function moneyToCents(money: string): bigint | null {
  return MONEY_PATTERN.test(money) ? BigInt(money.replace('.', '')) : null;
}

/** `ROUND((new - old) / old * 100, 2)` with HALF_UP in exact cents arithmetic; null when `old` is unusable. */
export function salaryChangePct(oldSalary: string, newSalary: string): string | null {
  const oldCents = moneyToCents(oldSalary);
  const newCents = moneyToCents(newSalary);
  if (oldCents === null || newCents === null || oldCents <= 0n) return null;
  const numerator = (newCents - oldCents) * 10000n;
  let hundredths = numerator / oldCents;
  const remainder = numerator % oldCents;
  if (remainder * 2n >= oldCents) hundredths += 1n;
  else if (remainder * -2n >= oldCents) hundredths -= 1n;
  const abs = hundredths < 0n ? -hundredths : hundredths;
  const sign = hundredths < 0n ? '-' : '';
  return `${sign}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`;
}

export function insertSalary(input: Omit<SalaryRecord, 'salaryId' | 'createdBy' | 'createdDate' | 'active' | 'endDate' | 'changePct'>, createdBy: string): SalaryRecord {
  const previous = activeSalaryFor(input.empId);
  let changePct: string | null = null;
  if (previous) {
    previous.active = false;
    previous.endDate = input.effectiveDate;
    changePct = salaryChangePct(previous.baseSalary, input.baseSalary);
  }
  const record: SalaryRecord = { ...input, salaryId: nextSalaryId++, endDate: null, changePct, active: true, createdBy, createdDate: new Date().toISOString() };
  salaries.push(record);
  return record;
}

export function historyFor(empId: number) {
  return history.filter((h) => h.empId === empId).sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate) || b.histId - a.histId);
}

export function insertHistory(entry: Omit<EmployeeHistoryEntry, 'histId' | 'createdDate'>): EmployeeHistoryEntry {
  const h: EmployeeHistoryEntry = { ...entry, histId: nextHistId++, createdDate: new Date().toISOString() };
  history.push(h);
  return h;
}

export function dependentsFor(empId: number) {
  return dependents.filter((d) => d.empId === empId && d.active).sort((a, b) => a.lastName.localeCompare(b.lastName) || a.firstName.localeCompare(b.firstName));
}

export function getDependent(empId: number, dependentId: number) {
  return dependents.find((d) => d.empId === empId && d.dependentId === dependentId && d.active);
}

export function insertDependent(d: Omit<Dependent, 'dependentId'>): Dependent {
  const created = { ...d, dependentId: nextDependentId++ };
  dependents.push(created);
  return created;
}

export function contactsFor(empId: number) {
  return contacts.filter((c) => c.empId === empId && c.active).sort((a, b) => a.priorityOrder - b.priorityOrder || a.contactId - b.contactId);
}

export function getContact(empId: number, contactId: number) {
  return contacts.find((c) => c.empId === empId && c.contactId === contactId && c.active);
}

export function insertContact(c: Omit<EmergencyContact, 'contactId'>): EmergencyContact {
  const created = { ...c, contactId: nextContactId++ };
  contacts.push(created);
  return created;
}
