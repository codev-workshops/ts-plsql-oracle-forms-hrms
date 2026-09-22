import { HttpResponse, http } from 'msw';
import type {
  ApiError,
  Authority,
  DependentRequest,
  EmergencyContactRequest,
  EmployeeCreateRequest,
  EmployeeTerminateRequest,
  EmployeeTransferRequest,
  EmployeeUpdateRequest,
  EmploymentStatus,
  PageOfEmployeeListItem,
  PageOfEmployeeSummary,
  SalaryChangeRequest,
} from '../api/types';
import { getDto, zodFor, type DtoName } from '../validation/schema';
import {
  activeSalaryFor,
  clone,
  contactsFor,
  departmentById,
  dependentsFor,
  getContact,
  getDependent,
  getEmployeeRow,
  historyFor,
  insertContact,
  insertDependent,
  insertEmployee,
  insertHistory,
  insertSalary,
  jobById,
  listEmployeeRows,
  locationByCode,
  salaryHistoryFor,
  toDetail,
  toListItem,
  toSummary,
  type EmployeeRow,
} from './employeeStore';

/**
 * msw implementation of contracts/p3-employee/openapi.yaml + error-codes.md. Writes always
 * succeed here (the mock stands in for `employee=NEW`); tests that need `MODULE_READ_ONLY`
 * override a route with `server.use`.
 */

interface SessionUser {
  userId: string;
  empId: number;
  username: string;
  roles: Authority[];
}

type Authenticate = (request: Request) => SessionUser | null;

const TRACE = '9c1e4b7a2d3f4e08';
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const STATUSES: EmploymentStatus[] = ['ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'TERMINATED'];

function error(status: number, body: Omit<ApiError, 'traceId'>) {
  return HttpResponse.json<ApiError>({ ...body, traceId: TRACE }, { status });
}

const unauthorized = () => error(401, { code: 'TOKEN_INVALID', message: 'Session has expired' });
const forbidden = () => error(403, { code: 'FORBIDDEN', message: 'You do not have permission to perform this action' });
const validation = (field: string, message = 'Request validation failed') => error(400, { code: 'VALIDATION_FAILED', message, field });
const employeeNotFound = () => error(404, { code: '-20001', message: 'Employee not found or not active' });

type PathValue = string | readonly string[] | undefined;

function num(v: PathValue) {
  return Number(Array.isArray(v) ? v[0] : v);
}

async function body<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    return {} as T;
  }
}

/** OpenAPI properties the generated schema does not (yet) describe — accepted as-is. */
const SCHEMA_GAPS: Partial<Record<DtoName, readonly string[]>> = {
  DependentRequest: ['active'],
  EmergencyContactRequest: ['active'],
};

/** Server-side re-check through the same generated schema the forms use (never a second rule set). */
function validateDto(dto: DtoName, payload: Record<string, unknown>) {
  const spec = getDto(dto);
  const extra = SCHEMA_GAPS[dto] ?? [];
  for (const name of Object.keys(payload)) if (!(name in spec.fields) && !extra.includes(name)) return validation(name, `Unknown property ${name}`);
  const candidate = Object.fromEntries(Object.entries(payload).filter(([, v]) => v !== null));
  const parsed = zodFor(dto).safeParse(candidate);
  if (parsed.success) return null;
  const issue = parsed.error.issues[0];
  const field = String(issue.path[0] ?? '');
  const params = (issue as { params?: { errorCode?: string } }).params;
  const code = params?.errorCode ?? 'VALIDATION_FAILED';
  return error(400, { code, message: issue.message, field, details: [{ field, code: 'Invalid', message: issue.message }] });
}

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function canSeeSsn(user: SessionUser, id: number) {
  return user.roles.includes('EMPLOYEE:EDIT') || user.empId === id;
}

function etagOf(row: EmployeeRow) {
  return `"${row.version}"`;
}

function detailResponse(user: SessionUser, row: EmployeeRow, status = 200, extraHeaders: Record<string, string> = {}) {
  return HttpResponse.json(toDetail(row, canSeeSsn(user, row.id)), { status, headers: { ETag: etagOf(row), ...extraHeaders } });
}

function emailInUse(email: string | null | undefined, exceptId: number | null) {
  if (!email) return false;
  return listEmployeeRows().some((e) => e.active && e.id !== exceptId && e.email?.toLowerCase() === email.toLowerCase());
}

function assertManager(managerEmpId: number | null | undefined, selfId: number | null) {
  if (managerEmpId === null || managerEmpId === undefined) return null;
  let cursor = getEmployeeRow(managerEmpId);
  if (!cursor?.active || cursor.employmentStatus !== 'ACTIVE') return error(422, { code: '-20004', message: `Invalid or inactive manager: ${managerEmpId}`, field: 'managerEmpId' });
  for (let depth = 0; cursor && depth < 50; depth++) {
    if (cursor.id === selfId) return error(422, { code: '-20004', message: `Invalid or inactive manager: ${managerEmpId}`, field: 'managerEmpId' });
    cursor = cursor.managerEmpId ? getEmployeeRow(cursor.managerEmpId) : undefined;
  }
  return null;
}

function applyRefs(row: EmployeeRow) {
  row.deptName = departmentById(row.deptId)?.deptName ?? row.deptName;
  const job = jobById(row.jobId);
  row.jobTitle = job?.jobTitle ?? row.jobTitle;
  row.gradeCode = job?.gradeCode ?? null;
  const manager = row.managerEmpId ? getEmployeeRow(row.managerEmpId) : undefined;
  row.managerName = manager ? `${manager.firstName} ${manager.lastName}` : null;
  row.locationName = locationByCode(row.locationCode)?.locationName ?? null;
}

const nullable = <T>(v: T | undefined): T | null => (v === undefined || v === '' ? null : v);

export function createEmployeeHandlers(authenticate: Authenticate) {
  return [
    http.get('/api/employees', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('EMPLOYEE:VIEW')) return forbidden();
      const url = new URL(request.url);
      const p = (k: string) => url.searchParams.get(k);
      const fields = p('fields');
      if (fields !== null && fields !== 'id,name,jobTitle') return validation('fields');
      const status = p('status');
      if (status !== null && !STATUSES.includes(status as EmploymentStatus)) return validation('status');
      const q = (p('q') ?? '').trim().toLowerCase();
      if (q.length > 100) return validation('q');
      const lastName = (p('lastName') ?? '').trim().toUpperCase();
      const firstName = (p('firstName') ?? '').trim().toUpperCase();
      if (lastName.length > 50) return validation('lastName');
      if (firstName.length > 50) return validation('firstName');
      const from = p('hireDateFrom');
      const to = p('hireDateTo');
      if ((from && !ISO_DATE.test(from)) || (to && !ISO_DATE.test(to))) return validation('hireDateFrom');
      if (from && to && from > to) return validation('hireDateTo', 'hireDateFrom must be on or before hireDateTo');
      const page = Number(p('page') ?? 0);
      const size = Number(p('size') ?? 20);
      if (page < 0 || size < 1 || size > 100) return validation('size');
      const deptId = p('deptId');
      const jobId = p('jobId');
      const managerEmpId = p('managerEmpId');
      const active = p('active');
      const locationCode = p('locationCode');

      let rows = listEmployeeRows().filter((e) => {
        if (status && e.employmentStatus !== status) return false;
        if (active !== null && e.active !== (active === 'true')) return false;
        if (deptId && e.deptId !== Number(deptId)) return false;
        if (jobId && e.jobId !== Number(jobId)) return false;
        if (managerEmpId && e.managerEmpId !== Number(managerEmpId)) return false;
        if (locationCode && e.locationCode !== locationCode) return false;
        if (lastName && !e.lastName.toUpperCase().startsWith(lastName)) return false;
        if (firstName && !e.firstName.toUpperCase().startsWith(firstName)) return false;
        if (from && e.hireDate < from) return false;
        if (to && e.hireDate > to) return false;
        if (q) {
          const name = `${e.firstName} ${e.lastName}`.toLowerCase();
          if (!name.includes(q) && !e.empNumber.toLowerCase().includes(q)) return false;
        }
        return true;
      });
      if (p('excludeSelf') === 'true') rows = rows.filter((e) => e.id !== user.empId);
      rows.sort((a, b) => a.lastName.localeCompare(b.lastName) || a.firstName.localeCompare(b.firstName) || a.id - b.id);
      const slice = rows.slice(page * size, page * size + size);
      const meta = { page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) };
      if (fields) {
        const out: PageOfEmployeeSummary = { content: slice.map(toSummary), ...meta };
        return HttpResponse.json(out);
      }
      const out: PageOfEmployeeListItem = { content: slice.map(toListItem), ...meta };
      return HttpResponse.json(out);
    }),

    http.post('/api/employees', async ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('EMPLOYEE:EDIT')) return forbidden();
      const b = await body<EmployeeCreateRequest>(request);
      const invalid = validateDto('EmployeeCreateRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      if (!b.firstName?.trim() || !b.lastName?.trim()) return error(400, { code: '-20010', message: 'First name and last name are required', field: 'lastName' });
      const dept = departmentById(b.deptId);
      if (!dept?.active) return error(422, { code: '-20003', message: `Invalid or inactive department: ${b.deptId}`, field: 'deptId' });
      const job = jobById(b.jobId);
      if (!job?.active) return error(422, { code: '-20011', message: `Invalid or inactive job: ${b.jobId}`, field: 'jobId' });
      const managerError = assertManager(b.managerEmpId, null);
      if (managerError) return managerError;
      if (emailInUse(b.email, null)) return error(409, { code: '-20502', message: `Email address already in use: ${b.email}`, field: 'email' });
      const ssn = nullable(b.ssn);
      const row = insertEmployee({
        firstName: b.firstName.trim(),
        middleName: nullable(b.middleName),
        lastName: b.lastName.trim(),
        dateOfBirth: nullable(b.dateOfBirth),
        gender: nullable(b.gender),
        maritalStatus: nullable(b.maritalStatus),
        nationality: nullable(b.nationality),
        ssn,
        ssnLast4: ssn ? ssn.replace(/\D/g, '').slice(-4) : null,
        email: nullable(b.email),
        phoneWork: nullable(b.phoneWork),
        phoneMobile: nullable(b.phoneMobile),
        addressLine1: nullable(b.addressLine1),
        addressLine2: nullable(b.addressLine2),
        city: nullable(b.city),
        stateProvince: nullable(b.stateProvince),
        postalCode: nullable(b.postalCode),
        countryCode: nullable(b.countryCode),
        hireDate: b.hireDate,
        terminationDate: null,
        terminationReason: null,
        deptId: b.deptId,
        deptName: dept.deptName,
        jobId: b.jobId,
        jobTitle: job.jobTitle,
        gradeCode: job.gradeCode,
        managerEmpId: nullable(b.managerEmpId),
        managerName: null,
        locationCode: nullable(b.locationCode),
        locationName: null,
        employmentType: b.employmentType ?? 'FULL_TIME',
        employmentStatus: 'ACTIVE',
        active: true,
        notes: nullable(b.notes),
        version: 0,
        createdBy: user.username,
        createdDate: new Date().toISOString(),
        modifiedBy: null,
        modifiedDate: null,
      });
      applyRefs(row);
      insertHistory({ empId: row.id, changeType: 'HIRE', effectiveDate: row.hireDate, oldDeptId: null, oldDeptName: null, newDeptId: row.deptId, newDeptName: row.deptName, oldJobId: null, oldJobTitle: null, newJobId: row.jobId, newJobTitle: row.jobTitle, oldManagerId: null, oldManagerName: null, newManagerId: row.managerEmpId, newManagerName: row.managerName, oldSalary: null, newSalary: b.initialSalary ?? null, oldLocation: null, newLocation: row.locationCode, reasonCode: 'HIRE', comments: null, createdBy: user.username });
      if (b.initialSalary) {
        const out = Number(b.initialSalary) < Number(job.gradeMinSalary) || Number(b.initialSalary) > Number(job.gradeMaxSalary);
        insertSalary({ empId: row.id, effectiveDate: row.hireDate, baseSalary: b.initialSalary, currencyCode: 'USD', payFrequency: 'MONTHLY', salaryBasis: 'ANNUAL', changeReason: 'HIRE', outOfGradeBand: out }, user.username);
      }
      return detailResponse(user, row, 201, { Location: `/api/employees/${row.id}` });
    }),

    http.get('/api/employees/:id', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('EMPLOYEE:VIEW')) return forbidden();
      const row = getEmployeeRow(num(params.id));
      if (!row) return employeeNotFound();
      return detailResponse(user, row);
    }),

    http.put('/api/employees/:id', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('EMPLOYEE:EDIT')) return forbidden();
      const ifMatch = request.headers.get('if-match');
      if (!ifMatch) return error(428, { code: 'PRECONDITION_REQUIRED', message: 'If-Match header is required' });
      const row = getEmployeeRow(num(params.id));
      if (!row) return employeeNotFound();
      if (ifMatch.replace(/"/g, '') !== String(row.version)) return error(409, { code: 'CONFLICT', message: 'Record was changed by another user' });
      const b = await body<EmployeeUpdateRequest>(request);
      const invalid = validateDto('EmployeeUpdateRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      if (row.employmentStatus === 'TERMINATED') return error(422, { code: '-20503', message: 'Cannot directly reactivate a terminated employee. Use the rehire process.' });
      const job = jobById(b.jobId);
      if (!job?.active) return error(422, { code: '-20011', message: `Invalid or inactive job: ${b.jobId}`, field: 'jobId' });
      const managerError = assertManager(b.managerEmpId, row.id);
      if (managerError) return managerError;
      if (emailInUse(b.email, row.id)) return error(409, { code: '-20502', message: `Email address already in use: ${b.email}`, field: 'email' });
      const oldJobId = row.jobId;
      const oldJobTitle = row.jobTitle;
      Object.assign(row, {
        firstName: b.firstName.trim(),
        middleName: nullable(b.middleName),
        lastName: b.lastName.trim(),
        dateOfBirth: nullable(b.dateOfBirth),
        gender: nullable(b.gender),
        maritalStatus: nullable(b.maritalStatus),
        nationality: nullable(b.nationality),
        email: nullable(b.email),
        phoneWork: nullable(b.phoneWork),
        phoneMobile: nullable(b.phoneMobile),
        addressLine1: nullable(b.addressLine1),
        addressLine2: nullable(b.addressLine2),
        city: nullable(b.city),
        stateProvince: nullable(b.stateProvince),
        postalCode: nullable(b.postalCode),
        countryCode: nullable(b.countryCode),
        jobId: b.jobId,
        managerEmpId: nullable(b.managerEmpId),
        employmentType: b.employmentType ?? row.employmentType,
        notes: nullable(b.notes),
        version: row.version + 1,
        modifiedBy: user.username,
        modifiedDate: new Date().toISOString(),
      });
      if (b.ssn) {
        row.ssn = b.ssn;
        row.ssnLast4 = b.ssn.replace(/\D/g, '').slice(-4);
      }
      applyRefs(row);
      if (oldJobId !== row.jobId) {
        insertHistory({ empId: row.id, changeType: 'PROMOTION', effectiveDate: todayIso(), oldDeptId: null, oldDeptName: null, newDeptId: null, newDeptName: null, oldJobId, oldJobTitle, newJobId: row.jobId, newJobTitle: row.jobTitle, oldManagerId: null, oldManagerName: null, newManagerId: null, newManagerName: null, oldSalary: null, newSalary: null, oldLocation: null, newLocation: null, reasonCode: null, comments: null, createdBy: user.username });
      }
      return detailResponse(user, row);
    }),

    http.post('/api/employees/:id/terminate', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('EMPLOYEE:EDIT')) return forbidden();
      const row = getEmployeeRow(num(params.id));
      if (!row) return employeeNotFound();
      const b = await body<EmployeeTerminateRequest>(request);
      const invalid = validateDto('EmployeeTerminateRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      if (b.effectiveDate < row.hireDate) return validation('effectiveDate', 'Termination date must be on or after the hire date');
      if (row.employmentStatus === 'TERMINATED') return error(422, { code: '-20005', message: `Employee ${row.id} is already terminated` });
      Object.assign(row, { employmentStatus: 'TERMINATED', active: false, terminationDate: b.effectiveDate, terminationReason: b.reason.trim(), version: row.version + 1, modifiedBy: user.username, modifiedDate: new Date().toISOString() });
      insertHistory({ empId: row.id, changeType: 'TERMINATION', effectiveDate: b.effectiveDate, oldDeptId: row.deptId, oldDeptName: row.deptName, newDeptId: null, newDeptName: null, oldJobId: row.jobId, oldJobTitle: row.jobTitle, newJobId: null, newJobTitle: null, oldManagerId: row.managerEmpId, oldManagerName: row.managerName, newManagerId: null, newManagerName: null, oldSalary: activeSalaryFor(row.id)?.baseSalary ?? null, newSalary: null, oldLocation: row.locationCode, newLocation: null, reasonCode: b.reason.trim(), comments: nullable(b.comments), createdBy: user.username });
      return detailResponse(user, row);
    }),

    http.post('/api/employees/:id/transfer', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('EMPLOYEE:EDIT')) return forbidden();
      const row = getEmployeeRow(num(params.id));
      if (!row) return employeeNotFound();
      const b = await body<EmployeeTransferRequest>(request);
      const invalid = validateDto('EmployeeTransferRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      if (row.employmentStatus !== 'ACTIVE') return error(422, { code: '-20012', message: `Cannot transfer non-active employee. Status: ${row.employmentStatus}` });
      const dept = departmentById(b.deptId);
      if (!dept?.active) return error(422, { code: '-20003', message: `Invalid or inactive department: ${b.deptId}`, field: 'deptId' });
      if (b.newJobId && !jobById(b.newJobId)?.active) return error(422, { code: '-20011', message: `Invalid or inactive job: ${b.newJobId}`, field: 'newJobId' });
      const managerError = assertManager(b.newManagerEmpId, row.id);
      if (managerError) return managerError;
      const before = clone(row);
      Object.assign(row, {
        deptId: b.deptId,
        jobId: b.newJobId ?? row.jobId,
        managerEmpId: b.newManagerEmpId ?? row.managerEmpId,
        locationCode: b.newLocationCode ?? row.locationCode,
        version: row.version + 1,
        modifiedBy: user.username,
        modifiedDate: new Date().toISOString(),
      });
      applyRefs(row);
      insertHistory({ empId: row.id, changeType: 'TRANSFER', effectiveDate: b.effectiveDate, oldDeptId: before.deptId, oldDeptName: before.deptName, newDeptId: row.deptId, newDeptName: row.deptName, oldJobId: before.jobId, oldJobTitle: before.jobTitle, newJobId: row.jobId, newJobTitle: row.jobTitle, oldManagerId: before.managerEmpId, oldManagerName: before.managerName, newManagerId: row.managerEmpId, newManagerName: row.managerName, oldSalary: null, newSalary: null, oldLocation: before.locationCode, newLocation: row.locationCode, reasonCode: nullable(b.reasonCode), comments: nullable(b.comments), createdBy: user.username });
      return detailResponse(user, row);
    }),

    // ---- salary-module ---------------------------------------------------------------
    http.get('/api/employees/:id/salary', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const id = num(params.id);
      if (!user.roles.includes('PAYROLL:VIEW') && !user.roles.includes('EMPLOYEE:EDIT') && user.empId !== id) return forbidden();
      const row = getEmployeeRow(id);
      if (!row) return employeeNotFound();
      const current = activeSalaryFor(id);
      if (!current) return error(400, { code: '-20104', message: `No active salary record for employee ${id}` });
      return HttpResponse.json(clone(current));
    }),

    http.post('/api/employees/:id/salary', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('PAYROLL:EDIT')) return forbidden();
      const row = getEmployeeRow(num(params.id));
      if (!row) return employeeNotFound();
      const b = await body<SalaryChangeRequest>(request);
      const invalid = validateDto('SalaryChangeRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      const current = activeSalaryFor(row.id);
      if (b.effectiveDate < row.hireDate || (current && b.effectiveDate < current.effectiveDate)) return validation('effectiveDate', 'Effective date must be on or after the current salary record');
      const job = jobById(row.jobId);
      const out = !!job && (Number(b.baseSalary) < Number(job.gradeMinSalary) || Number(b.baseSalary) > Number(job.gradeMaxSalary));
      const record = insertSalary({ empId: row.id, effectiveDate: b.effectiveDate, baseSalary: b.baseSalary, currencyCode: b.currencyCode ?? 'USD', payFrequency: b.payFrequency ?? current?.payFrequency ?? 'MONTHLY', salaryBasis: b.salaryBasis ?? current?.salaryBasis ?? 'ANNUAL', changeReason: b.changeReason.trim(), outOfGradeBand: out }, user.username);
      insertHistory({ empId: row.id, changeType: 'SALARY_CHANGE', effectiveDate: b.effectiveDate, oldDeptId: null, oldDeptName: null, newDeptId: null, newDeptName: null, oldJobId: null, oldJobTitle: null, newJobId: null, newJobTitle: null, oldManagerId: null, oldManagerName: null, newManagerId: null, newManagerName: null, oldSalary: current?.baseSalary ?? null, newSalary: record.baseSalary, oldLocation: null, newLocation: null, reasonCode: record.changeReason, comments: null, createdBy: user.username });
      return HttpResponse.json(clone(record), { status: 201 });
    }),

    http.get('/api/employees/:id/salary/history', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const id = num(params.id);
      if (!user.roles.includes('PAYROLL:VIEW') && !user.roles.includes('EMPLOYEE:EDIT') && user.empId !== id) return forbidden();
      if (!getEmployeeRow(id)) return employeeNotFound();
      return HttpResponse.json(clone(salaryHistoryFor(id)));
    }),

    // ---- history / dependents / contacts (employee-service) ---------------------------
    http.get('/api/employees/:id/history', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('EMPLOYEE:VIEW')) return forbidden();
      const id = num(params.id);
      if (!getEmployeeRow(id)) return employeeNotFound();
      return HttpResponse.json(clone(historyFor(id)));
    }),

    http.get('/api/employees/:id/dependents', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const id = num(params.id);
      if (!user.roles.includes('EMPLOYEE:EDIT') && user.empId !== id) return forbidden();
      if (!getEmployeeRow(id)) return employeeNotFound();
      return HttpResponse.json(clone(dependentsFor(id)));
    }),

    http.post('/api/employees/:id/dependents', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const id = num(params.id);
      if (!user.roles.includes('EMPLOYEE:EDIT') && user.empId !== id) return forbidden();
      const row = getEmployeeRow(id);
      if (!row) return employeeNotFound();
      const b = await body<DependentRequest>(request);
      const invalid = validateDto('DependentRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      if (row.employmentStatus === 'TERMINATED') return error(422, { code: '-20503', message: 'Cannot directly reactivate a terminated employee. Use the rehire process.' });
      const created = insertDependent({ empId: id, firstName: b.firstName.trim(), lastName: b.lastName.trim(), relationship: b.relationship, dateOfBirth: nullable(b.dateOfBirth), ssnLast4: b.ssn ? b.ssn.replace(/\D/g, '').slice(-4) : null, benefitsEnrolled: b.benefitsEnrolled ?? false, active: true });
      return HttpResponse.json(clone(created), { status: 201 });
    }),

    http.put('/api/employees/:id/dependents/:dependentId', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const id = num(params.id);
      if (!user.roles.includes('EMPLOYEE:EDIT') && user.empId !== id) return forbidden();
      const row = getEmployeeRow(id);
      if (!row) return employeeNotFound();
      const dep = getDependent(id, num(params.dependentId));
      if (!dep) return error(404, { code: 'DEPENDENT_NOT_FOUND', message: 'Dependent not found' });
      const b = await body<DependentRequest>(request);
      const invalid = validateDto('DependentRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      Object.assign(dep, { firstName: b.firstName.trim(), lastName: b.lastName.trim(), relationship: b.relationship, dateOfBirth: nullable(b.dateOfBirth), benefitsEnrolled: b.benefitsEnrolled ?? dep.benefitsEnrolled, active: b.active ?? true });
      if (b.ssn) dep.ssnLast4 = b.ssn.replace(/\D/g, '').slice(-4);
      return HttpResponse.json(clone(dep));
    }),

    http.get('/api/employees/:id/contacts', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const id = num(params.id);
      if (!user.roles.includes('EMPLOYEE:EDIT') && user.empId !== id) return forbidden();
      if (!getEmployeeRow(id)) return employeeNotFound();
      return HttpResponse.json(clone(contactsFor(id)));
    }),

    http.post('/api/employees/:id/contacts', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const id = num(params.id);
      if (!user.roles.includes('EMPLOYEE:EDIT') && user.empId !== id) return forbidden();
      const row = getEmployeeRow(id);
      if (!row) return employeeNotFound();
      const b = await body<EmergencyContactRequest>(request);
      const invalid = validateDto('EmergencyContactRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      if (row.employmentStatus === 'TERMINATED') return error(422, { code: '-20503', message: 'Cannot directly reactivate a terminated employee. Use the rehire process.' });
      const created = insertContact({ empId: id, contactName: b.contactName.trim(), relationship: nullable(b.relationship), phonePrimary: b.phonePrimary, phoneSecondary: nullable(b.phoneSecondary), email: nullable(b.email), priorityOrder: b.priorityOrder ?? 1, active: true });
      return HttpResponse.json(clone(created), { status: 201 });
    }),

    http.put('/api/employees/:id/contacts/:contactId', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const id = num(params.id);
      if (!user.roles.includes('EMPLOYEE:EDIT') && user.empId !== id) return forbidden();
      const row = getEmployeeRow(id);
      if (!row) return employeeNotFound();
      const contact = getContact(id, num(params.contactId));
      if (!contact) return error(404, { code: 'CONTACT_NOT_FOUND', message: 'Emergency contact not found' });
      const b = await body<EmergencyContactRequest>(request);
      const invalid = validateDto('EmergencyContactRequest', b as unknown as Record<string, unknown>);
      if (invalid) return invalid;
      Object.assign(contact, { contactName: b.contactName.trim(), relationship: nullable(b.relationship), phonePrimary: b.phonePrimary, phoneSecondary: nullable(b.phoneSecondary), email: nullable(b.email), priorityOrder: b.priorityOrder ?? contact.priorityOrder, active: b.active ?? true });
      return HttpResponse.json(clone(contact));
    }),
  ];
}
