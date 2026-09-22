import type {
  Dependent,
  DepartmentRef,
  EmergencyContact,
  EmployeeDetail,
  EmployeeHistoryEntry,
  EmployeeListItem,
  EmployeeSummary,
  HistoryChangeType,
  JobTitleRef,
  LocationRef,
  SalaryRecord,
} from '../api/types';

/**
 * In-memory `employee-service` + `salary-module` state for the msw contract mocks
 * (contracts/p3-employee/openapi.yaml). Names mirror e2e/seed-accounts.ts and
 * src/mocks/performanceStore.ts; reference rows come from handlers.ts.
 */

export interface MockEmployee extends EmployeeDetail {
  /** Encrypted at rest in the target; the mock keeps it only to derive `ssnLast4`. */
  ssn: string | null;
}

interface Refs {
  departments: DepartmentRef[];
  jobTitles: JobTitleRef[];
  locations: LocationRef[];
}

let refs: Refs = { departments: [], jobTitles: [], locations: [] };
let employees: MockEmployee[] = [];
let salaries: SalaryRecord[] = [];
let history: EmployeeHistoryEntry[] = [];
let dependents: (Dependent & { ssn: string | null })[] = [];
let contacts: EmergencyContact[] = [];
let nextEmpId = 100;
let nextEmpNumber = 100;
let nextSalaryId = 500;
let nextHistId = 900;
let nextDependentId = 300;
let nextContactId = 400;
const SEED_TS = '2024-01-15T09:00:00Z';

export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

export function isoDay(offsetDays: number, from = new Date()): string {
  const d = new Date(Date.UTC(from.getUTCFullYear(), from.getUTCMonth(), from.getUTCDate() + offsetDays));
  return d.toISOString().slice(0, 10);
}

export function dept(deptId: number) {
  return refs.departments.find((d) => d.deptId === deptId);
}

export function job(jobId: number) {
  return refs.jobTitles.find((j) => j.jobId === jobId);
}

export function location(code: string | null | undefined) {
  return code ? refs.locations.find((l) => l.locationCode === code) : undefined;
}

export function fullName(e: Pick<EmployeeDetail, 'firstName' | 'lastName'>) {
  return `${e.firstName} ${e.lastName}`;
}

function seed(
  id: number,
  firstName: string,
  lastName: string,
  deptId: number,
  jobId: number,
  managerEmpId: number | null,
  locationCode: string,
  hireDate: string,
  extra: Partial<MockEmployee> = {},
): MockEmployee {
  const d = dept(deptId)!;
  const j = job(jobId)!;
  const manager = managerEmpId ? employees.find((e) => e.id === managerEmpId) : undefined;
  return {
    id,
    empNumber: `EMP-${String(id).padStart(6, '0')}`,
    firstName,
    middleName: null,
    lastName,
    dateOfBirth: '1985-06-15',
    gender: null,
    maritalStatus: null,
    nationality: null,
    ssn: null,
    ssnLast4: null,
    email: `${firstName.toLowerCase()}.${lastName.toLowerCase()}@company.com`,
    phoneWork: '212-555-0100',
    phoneMobile: null,
    addressLine1: null,
    addressLine2: null,
    city: location(locationCode)?.city ?? null,
    stateProvince: location(locationCode)?.stateProvince ?? null,
    postalCode: null,
    countryCode: 'USA',
    hireDate,
    terminationDate: null,
    terminationReason: null,
    deptId,
    deptName: d.deptName,
    jobId,
    jobTitle: j.jobTitle,
    gradeCode: j.gradeCode,
    managerEmpId,
    managerName: manager ? fullName(manager) : null,
    locationCode,
    locationName: location(locationCode)?.locationName ?? null,
    employmentType: 'FULL_TIME',
    employmentStatus: 'ACTIVE',
    active: true,
    notes: null,
    version: 0,
    createdBy: 'SYSTEM',
    createdDate: SEED_TS,
    modifiedBy: null,
    modifiedDate: null,
    ...extra,
  };
}

function seedSalary(empId: number, effectiveDate: string, endDate: string | null, base: string, reason: string, pct: string | null, outOfBand = false): SalaryRecord {
  return {
    salaryId: nextSalaryId++,
    empId,
    effectiveDate,
    endDate,
    baseSalary: base,
    currencyCode: 'USD',
    payFrequency: 'MONTHLY',
    salaryBasis: 'ANNUAL',
    changeReason: reason,
    changePct: pct,
    active: endDate === null,
    outOfGradeBand: outOfBand,
    createdBy: 'SYSTEM',
    createdDate: SEED_TS,
  };
}

function seedHistory(empId: number, changeType: HistoryChangeType, effectiveDate: string, extra: Partial<EmployeeHistoryEntry> = {}): EmployeeHistoryEntry {
  return {
    histId: nextHistId++,
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
    createdBy: 'SYSTEM',
    createdDate: SEED_TS,
    ...extra,
  };
}

export function resetEmployeeState(reference: Refs) {
  refs = reference;
  employees = [];
  nextSalaryId = 500;
  nextHistId = 900;
  nextDependentId = 300;
  nextContactId = 400;
  nextEmpId = 100;
  nextEmpNumber = 100;
  employees.push(seed(1, 'JAMES', 'RICHARDSON', 1, 3, null, 'HQ', '2010-01-04', { ssn: '123-45-0001' }));
  employees.push(seed(4, 'MIA', 'MANAGER', 1, 4, 1, 'HQ', '2015-03-02'));
  employees.push(seed(21, 'JENNIFER', 'PARK', 3, 4, 1, 'SF', '2016-09-12', { ssn: '123-45-0021' }));
  employees.push(seed(11, 'DAVID', 'MARTINEZ', 3, 1, 21, 'SF', '2019-02-18', { ssn: '123-45-0011', phoneMobile: '415-555-0111', version: 2, modifiedBy: 'HR', modifiedDate: '2024-03-01T10:00:00Z' }));
  employees.push(seed(12, 'EMILY', 'JOHNSON', 2, 1, 21, 'HQ', '2021-06-01'));
  employees.push(seed(13, 'SARAH', 'LEE', 3, 1, 21, 'SF', '2022-01-10', { employmentType: 'PART_TIME' }));
  employees.push(
    seed(30, 'ROBERT', 'OLDMAN', 2, 1, 21, 'CHI', '2012-05-01', {
      employmentStatus: 'TERMINATED',
      active: false,
      terminationDate: '2023-11-30',
      terminationReason: 'RESIGNATION',
    }),
  );

  salaries = [
    seedSalary(1, '2010-01-04', null, '250000.00', 'INITIAL', null, true),
    seedSalary(4, '2015-03-02', null, '98000.00', 'INITIAL', null),
    seedSalary(21, '2016-09-12', null, '105000.00', 'INITIAL', null),
    seedSalary(11, '2019-02-18', '2022-03-31', '48000.00', 'INITIAL', null),
    seedSalary(11, '2022-04-01', null, '55000.00', 'MERIT', '14.58'),
    seedSalary(12, '2021-06-01', null, '50000.00', 'INITIAL', null),
    seedSalary(13, '2022-01-10', null, '30000.00', 'INITIAL', null, true),
    seedSalary(30, '2012-05-01', '2023-11-30', '52000.00', 'INITIAL', null),
  ];

  history = [
    seedHistory(1, 'HIRE', '2010-01-04', { newDeptId: 1, newDeptName: 'Human Resources', newJobId: 3, newJobTitle: 'CEO', newSalary: '250000.00', newLocation: 'HQ' }),
    seedHistory(11, 'HIRE', '2019-02-18', { newDeptId: 3, newDeptName: 'Engineering', newJobId: 1, newJobTitle: 'Analyst', newManagerId: 21, newManagerName: 'JENNIFER PARK', newSalary: '48000.00', newLocation: 'SF' }),
    seedHistory(11, 'SALARY_CHANGE', '2022-04-01', { oldSalary: '48000.00', newSalary: '55000.00', reasonCode: 'MERIT', createdBy: 'HR', createdDate: '2022-04-01T08:00:00Z' }),
    seedHistory(30, 'HIRE', '2012-05-01', { newDeptId: 2, newDeptName: 'Finance', newJobId: 1, newJobTitle: 'Analyst', newSalary: '52000.00' }),
    seedHistory(30, 'TERMINATION', '2023-11-30', { reasonCode: 'RESIGNATION', comments: 'Moved abroad', oldSalary: '52000.00' }),
  ];

  dependents = [
    { dependentId: 301, empId: 11, firstName: 'LUCIA', lastName: 'MARTINEZ', relationship: 'SPOUSE', dateOfBirth: '1986-09-09', ssn: '987-65-4321', ssnLast4: '4321', benefitsEnrolled: true, active: true },
    { dependentId: 302, empId: 11, firstName: 'MATEO', lastName: 'MARTINEZ', relationship: 'CHILD', dateOfBirth: '2015-02-02', ssn: null, ssnLast4: null, benefitsEnrolled: true, active: true },
    { dependentId: 303, empId: 11, firstName: 'OLD', lastName: 'RECORD', relationship: 'OTHER', dateOfBirth: null, ssn: null, ssnLast4: null, benefitsEnrolled: false, active: false },
  ];

  contacts = [
    { contactId: 401, empId: 11, contactName: 'LUCIA MARTINEZ', relationship: 'Spouse', phonePrimary: '415-555-0199', phoneSecondary: null, email: 'lucia@example.com', priorityOrder: 1, active: true },
    { contactId: 402, empId: 21, contactName: 'KEVIN PARK', relationship: 'Brother', phonePrimary: '650-555-0122', phoneSecondary: null, email: null, priorityOrder: 1, active: true },
  ];
}

export function listMockEmployees() {
  return employees;
}

export function getEmployee(id: number) {
  return employees.find((e) => e.id === id);
}

export function nextEmployeeIds() {
  nextEmpId += 1;
  nextEmpNumber += 1;
  return { id: nextEmpId, empNumber: `EMP-${String(nextEmpNumber).padStart(6, '0')}` };
}

export function insertEmployee(e: MockEmployee) {
  employees.push(e);
  return e;
}

/** `EmployeeDetail` projection with the `ssnLast4` scope applied. */
export function toDetail(e: MockEmployee, ssnScope: boolean): EmployeeDetail {
  const { ssn, ...rest } = clone(e);
  return { ...rest, ssnLast4: ssnScope && ssn ? ssn.replace(/\D/g, '').slice(-4) : null };
}

export function toListItem(e: MockEmployee): EmployeeListItem {
  return {
    id: e.id,
    empNumber: e.empNumber,
    firstName: e.firstName,
    lastName: e.lastName,
    email: e.email ?? null,
    deptId: e.deptId,
    deptName: e.deptName,
    jobId: e.jobId,
    jobTitle: e.jobTitle,
    managerEmpId: e.managerEmpId ?? null,
    managerName: e.managerName ?? null,
    locationCode: e.locationCode ?? null,
    hireDate: e.hireDate,
    employmentType: e.employmentType,
    employmentStatus: e.employmentStatus,
    active: e.active,
  };
}

export function toSummary(e: MockEmployee): EmployeeSummary {
  return { id: e.id, empNumber: e.empNumber, name: fullName(e), jobTitle: e.jobTitle };
}

/** Walk up from `managerId`; true when the chain reaches `subjectId` (or exceeds 50 levels – DATA-02). */
export function reportsTo(managerId: number, subjectId: number): boolean {
  let cur: number | null | undefined = managerId;
  for (let depth = 0; cur !== null && cur !== undefined; depth++) {
    if (cur === subjectId || depth > 50) return true;
    cur = getEmployee(cur)?.managerEmpId;
  }
  return false;
}

export function activeSalary(empId: number) {
  return salaries.find((s) => s.empId === empId && s.active);
}

export function salaryHistory(empId: number) {
  return salaries.filter((s) => s.empId === empId).sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate) || b.salaryId - a.salaryId);
}

export function insertSalary(record: Omit<SalaryRecord, 'salaryId'>, endDate: string) {
  const current = activeSalary(record.empId);
  if (current) {
    current.active = false;
    current.endDate = endDate;
  }
  const row: SalaryRecord = { ...record, salaryId: nextSalaryId++ };
  salaries.push(row);
  return row;
}

export function closeSalary(empId: number, endDate: string) {
  const current = activeSalary(empId);
  if (current) {
    current.active = false;
    current.endDate = endDate;
  }
}

export function historyFor(empId: number) {
  return history.filter((h) => h.empId === empId).sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate) || b.histId - a.histId);
}

export function insertHistory(entry: Omit<EmployeeHistoryEntry, 'histId'>) {
  const row: EmployeeHistoryEntry = { ...entry, histId: nextHistId++ };
  history.push(row);
  return row;
}

export function dependentsFor(empId: number) {
  return dependents.filter((d) => d.empId === empId && d.active).sort((a, b) => a.lastName.localeCompare(b.lastName) || a.firstName.localeCompare(b.firstName));
}

export function getDependent(empId: number, dependentId: number) {
  return dependents.find((d) => d.empId === empId && d.dependentId === dependentId && d.active);
}

export function insertDependent(d: Omit<Dependent, 'dependentId'> & { ssn: string | null }) {
  const row = { ...d, dependentId: nextDependentId++ };
  dependents.push(row);
  return row;
}

export function toDependent(d: Dependent & { ssn: string | null }): Dependent {
  const { ssn, ...rest } = clone(d);
  return { ...rest, ssnLast4: ssn ? ssn.replace(/\D/g, '').slice(-4) : null };
}

export function contactsFor(empId: number) {
  return contacts.filter((c) => c.empId === empId && c.active).sort((a, b) => a.priorityOrder - b.priorityOrder || a.contactId - b.contactId);
}

export function getContact(empId: number, contactId: number) {
  return contacts.find((c) => c.empId === empId && c.contactId === contactId && c.active);
}

export function insertContact(c: Omit<EmergencyContact, 'contactId'>) {
  const row = { ...c, contactId: nextContactId++ };
  contacts.push(row);
  return row;
}
