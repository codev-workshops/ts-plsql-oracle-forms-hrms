import { HttpResponse, http } from 'msw';
import type { z } from 'zod';
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
  SalaryRecord,
} from '../api/types';
import type { ModuleFlagValue } from '../app/modules';
import { type DtoName, zodFor } from '../validation/schema';
import {
  activeSalary,
  clone,
  contactsFor,
  closeSalary,
  dept,
  dependentsFor,
  fullName,
  getContact,
  getDependent,
  getEmployee,
  historyFor,
  insertContact,
  insertDependent,
  insertEmployee,
  insertHistory,
  insertSalary,
  isoDay,
  job,
  listMockEmployees,
  location,
  type MockEmployee,
  nextEmployeeIds,
  reportsTo,
  salaryHistory,
  toDependent,
  toDetail,
  toListItem,
  toSummary,
} from './employeeStore';

/**
 * msw implementation of contracts/p3-employee/openapi.yaml + error-codes.md (evaluation order
 * §3: auth → authority → module flag → headers → bean validation → existence → scope → lifecycle →
 * references → uniqueness → optimistic lock). Stand-in until employee-service / salary-module land.
 */

interface SessionUser {
  userId: string;
  empId: number;
  roles: Authority[];
}

type Authenticate = (request: Request) => SessionUser | null;

const TRACE = '9c4e1a7b2d3f4e05';
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

let moduleFlag: ModuleFlagValue = 'NEW';

/** Test helper: the backend guard for `employee=NEW_READONLY` (writes → 409 MODULE_READ_ONLY). */
export function setMockEmployeeModuleFlag(flag: ModuleFlagValue) {
  moduleFlag = flag;
}

function error(status: number, body: Omit<ApiError, 'traceId'>) {
  return HttpResponse.json<ApiError>({ ...body, traceId: TRACE }, { status });
}

const unauthorized = () => error(401, { code: 'TOKEN_INVALID', message: 'Session has expired' });
const forbidden = () => error(403, { code: 'FORBIDDEN', message: 'You do not have permission to perform this action' });
const employeeNotFound = () => error(404, { code: '-20001', message: 'Employee not found or not active' });
const readOnly = () => error(409, { code: 'MODULE_READ_ONLY', message: 'Employee module is read-only during cutover' });
const validation = (field: string, message = 'Request validation failed') => error(400, { code: 'VALIDATION_FAILED', message, field });

async function body<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    return {} as T;
  }
}

type PathValue = string | readonly string[] | undefined;

function num(v: PathValue) {
  return Number(Array.isArray(v) ? v[0] : v);
}

function has(user: SessionUser, authority: Authority) {
  return user.roles.includes(authority);
}

/** `ssnLast4` / salary read scope: `EMPLOYEE:EDIT`, `PAYROLL:VIEW` (salary only) or self. */
function ssnScope(user: SessionUser, empId: number) {
  return has(user, 'EMPLOYEE:EDIT') || user.empId === empId;
}
function salaryScope(user: SessionUser, empId: number) {
  return has(user, 'PAYROLL:VIEW') || has(user, 'EMPLOYEE:EDIT') || user.empId === empId;
}
function subResourceScope(user: SessionUser, empId: number) {
  return has(user, 'EMPLOYEE:EDIT') || user.empId === empId;
}

/** Bean Validation stand-in: the generated schema (hrms-validation) decides code + field. */
function validate(dto: DtoName, payload: unknown): ReturnType<typeof error> | null {
  const parsed = zodFor(dto).safeParse(payload ?? {});
  if (parsed.success) return null;
  const issue = parsed.error.issues[0];
  const field = String(issue.path[0] ?? '');
  const errorCode = (issue as z.ZodIssue & { params?: { errorCode?: string } }).params?.errorCode;
  if ((dto === 'EmployeeCreateRequest' || dto === 'EmployeeUpdateRequest') && (field === 'firstName' || field === 'lastName') && issue.code === 'too_small') {
    return error(400, { code: '-20010', message: 'First name and last name are required', field });
  }
  if (errorCode && errorCode !== 'VALIDATION_FAILED') return error(400, { code: errorCode, message: issue.message, field });
  return error(400, {
    code: 'VALIDATION_FAILED',
    message: 'Request validation failed',
    field,
    details: parsed.error.issues.map((i) => ({ field: String(i.path[0] ?? ''), code: i.code, message: i.message })),
  });
}

function nowIso() {
  return new Date().toISOString();
}

function touch(e: MockEmployee, user: SessionUser) {
  e.version += 1;
  e.modifiedBy = user.userId;
  e.modifiedDate = nowIso();
}

function applyReferences(e: MockEmployee) {
  e.deptName = dept(e.deptId)?.deptName ?? e.deptName;
  const j = job(e.jobId);
  if (j) {
    e.jobTitle = j.jobTitle;
    e.gradeCode = j.gradeCode;
  }
  const m = e.managerEmpId ? getEmployee(e.managerEmpId) : undefined;
  e.managerName = m ? fullName(m) : null;
  e.locationName = location(e.locationCode)?.locationName ?? null;
}

function managerError(subjectId: number | null, managerEmpId: number, field: string) {
  const m = getEmployee(managerEmpId);
  if (!m || m.employmentStatus !== 'ACTIVE' || !m.active) return error(400, { code: '-20004', message: `Invalid or inactive manager: ${managerEmpId}`, field });
  if (subjectId !== null && (managerEmpId === subjectId || reportsTo(managerEmpId, subjectId))) {
    return error(400, { code: '-20004', message: `Circular reporting chain detected: Employee ${subjectId} cannot report to ${managerEmpId}`, field });
  }
  return null;
}

function emailInUse(email: string | null | undefined, excludeId: number | null) {
  if (!email) return false;
  const upper = email.trim().toUpperCase();
  return listMockEmployees().some((e) => e.id !== excludeId && e.active && e.email?.toUpperCase() === upper);
}

function outOfBand(jobId: number, base: string) {
  const j = job(jobId);
  if (!j) return false;
  const n = Number(base);
  return n < Number(j.gradeMinSalary) || n > Number(j.gradeMaxSalary);
}

function money(n: number) {
  return n.toFixed(2);
}

function pct(oldBase: string | undefined, newBase: string): string | null {
  if (!oldBase) return null;
  const o = Number(oldBase);
  if (o === 0) return null;
  return (Math.round(((Number(newBase) - o) / o) * 10000) / 100).toFixed(2);
}

const STATUSES: EmploymentStatus[] = ['ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'TERMINATED'];

export function createEmployeeHandlers(authenticate: Authenticate) {
  const guard = (request: Request, authority: Authority | null, write: boolean) => {
    const user = authenticate(request);
    if (!user) return { user: null, response: unauthorized() };
    if (authority && !has(user, authority)) return { user: null, response: forbidden() };
    if (write && moduleFlag !== 'NEW') return { user: null, response: readOnly() };
    return { user, response: null };
  };

  return [
    http.get('/api/employees', ({ request }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', false);
      if (!user) return response;
      const url = new URL(request.url);
      const p = url.searchParams;
      const page = Number(p.get('page') ?? 0);
      const size = Number(p.get('size') ?? 20);
      if (page < 0 || size < 1 || size > 100) return validation('size');
      const q = (p.get('q') ?? '').trim().toLowerCase();
      if (q.length > 100) return validation('q');
      const fields = p.get('fields');
      if (fields !== null && fields !== 'id,name,jobTitle') return validation('fields');
      const status = p.get('status');
      if (status !== null && !STATUSES.includes(status as EmploymentStatus)) return validation('status');
      const from = p.get('hireDateFrom');
      const to = p.get('hireDateTo');
      if ((from && !ISO_DATE.test(from)) || (to && !ISO_DATE.test(to))) return validation('hireDateFrom');
      if (from && to && from > to) return validation('hireDateTo', 'hireDateFrom must be before or equal to hireDateTo');
      const activeParam = p.get('active');

      let rows = listMockEmployees().filter((e) => {
        if (q && !fullName(e).toLowerCase().includes(q) && !e.empNumber.toLowerCase().includes(q) && !(e.email ?? '').toLowerCase().includes(q)) return false;
        if (p.get('lastName') && !e.lastName.toLowerCase().startsWith(p.get('lastName')!.toLowerCase())) return false;
        if (p.get('firstName') && !e.firstName.toLowerCase().startsWith(p.get('firstName')!.toLowerCase())) return false;
        if (p.get('deptId') && e.deptId !== Number(p.get('deptId'))) return false;
        if (p.get('jobId') && e.jobId !== Number(p.get('jobId'))) return false;
        if (p.get('managerEmpId') && e.managerEmpId !== Number(p.get('managerEmpId'))) return false;
        if (status && e.employmentStatus !== status) return false;
        if (activeParam !== null && e.active !== (activeParam === 'true')) return false;
        if (p.get('locationCode') && e.locationCode !== p.get('locationCode')) return false;
        if (from && e.hireDate < from) return false;
        if (to && e.hireDate > to) return false;
        return true;
      });
      if (p.get('excludeSelf') === 'true') rows = rows.filter((e) => e.id !== user.empId);
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
      const { user, response } = guard(request, 'EMPLOYEE:EDIT', true);
      if (!user) return response;
      const req = await body<EmployeeCreateRequest>(request);
      const invalid = validate('EmployeeCreateRequest', req);
      if (invalid) return invalid;
      const d = dept(req.deptId);
      if (!d?.active) return error(400, { code: '-20003', message: `Invalid or inactive department: ${req.deptId}`, field: 'deptId' });
      const j = job(req.jobId);
      if (!j?.active) return error(400, { code: '-20011', message: `Invalid or inactive job: ${req.jobId}`, field: 'jobId' });
      if (req.managerEmpId != null) {
        const me = managerError(null, req.managerEmpId, 'managerEmpId');
        if (me) return me;
      }
      if (emailInUse(req.email, null)) return error(409, { code: '-20502', message: `Email address already in use: ${req.email!.trim()}`, field: 'email' });

      const ids = nextEmployeeIds();
      const e: MockEmployee = {
        id: ids.id,
        empNumber: ids.empNumber,
        firstName: req.firstName.trim(),
        middleName: req.middleName ?? null,
        lastName: req.lastName.trim(),
        dateOfBirth: req.dateOfBirth ?? null,
        gender: req.gender ?? null,
        maritalStatus: req.maritalStatus ?? null,
        nationality: req.nationality ?? null,
        ssn: req.ssn ?? null,
        ssnLast4: null,
        email: req.email?.trim() ?? null,
        phoneWork: req.phoneWork ?? null,
        phoneMobile: req.phoneMobile ?? null,
        addressLine1: req.addressLine1 ?? null,
        addressLine2: req.addressLine2 ?? null,
        city: req.city ?? null,
        stateProvince: req.stateProvince ?? null,
        postalCode: req.postalCode ?? null,
        countryCode: req.countryCode ?? null,
        hireDate: req.hireDate,
        terminationDate: null,
        terminationReason: null,
        deptId: req.deptId,
        deptName: d.deptName,
        jobId: req.jobId,
        jobTitle: j.jobTitle,
        gradeCode: j.gradeCode,
        managerEmpId: req.managerEmpId ?? null,
        managerName: null,
        locationCode: req.locationCode ?? null,
        locationName: null,
        employmentType: req.employmentType ?? 'FULL_TIME',
        employmentStatus: 'ACTIVE',
        active: true,
        notes: req.notes ?? null,
        version: 0,
        createdBy: user.userId,
        createdDate: nowIso(),
        modifiedBy: null,
        modifiedDate: null,
      };
      applyReferences(e);
      insertEmployee(e);
      if (req.initialSalary != null) {
        const base = money(Number(req.initialSalary));
        insertSalary(
          { empId: e.id, effectiveDate: e.hireDate, endDate: null, baseSalary: base, currencyCode: 'USD', payFrequency: 'MONTHLY', salaryBasis: 'ANNUAL', changeReason: 'INITIAL', changePct: null, active: true, outOfGradeBand: outOfBand(e.jobId, base), createdBy: user.userId, createdDate: nowIso() },
          e.hireDate,
        );
      }
      insertHistory({
        empId: e.id, changeType: 'HIRE', effectiveDate: e.hireDate,
        oldDeptId: null, oldDeptName: null, newDeptId: e.deptId, newDeptName: e.deptName,
        oldJobId: null, oldJobTitle: null, newJobId: e.jobId, newJobTitle: e.jobTitle,
        oldManagerId: null, oldManagerName: null, newManagerId: e.managerEmpId ?? null, newManagerName: e.managerName ?? null,
        oldSalary: null, newSalary: req.initialSalary != null ? money(Number(req.initialSalary)) : null,
        oldLocation: null, newLocation: e.locationCode ?? null, reasonCode: 'HIRE', comments: null,
        createdBy: user.userId, createdDate: nowIso(),
      });
      return HttpResponse.json(toDetail(e, true), { status: 201, headers: { Location: `/api/employees/${e.id}`, ETag: `"${e.version}"` } });
    }),

    http.get('/api/employees/:id', ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', false);
      if (!user) return response;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      return HttpResponse.json(toDetail(e, ssnScope(user, e.id)), { headers: { ETag: `"${e.version}"` } });
    }),

    http.put('/api/employees/:id', async ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:EDIT', true);
      if (!user) return response;
      const ifMatch = request.headers.get('if-match');
      if (!ifMatch) return error(428, { code: 'PRECONDITION_REQUIRED', message: 'If-Match header is required', field: 'If-Match' });
      const req = await body<EmployeeUpdateRequest>(request);
      const invalid = validate('EmployeeUpdateRequest', req);
      if (invalid) return invalid;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (e.employmentStatus === 'TERMINATED') return error(422, { code: '-20503', message: 'Cannot directly reactivate a terminated employee. Use the rehire process.' });
      const j = job(req.jobId);
      if (!j?.active) return error(400, { code: '-20011', message: `Invalid or inactive job: ${req.jobId}`, field: 'jobId' });
      if (req.managerEmpId != null) {
        const me = managerError(e.id, req.managerEmpId, 'managerEmpId');
        if (me) return me;
      }
      if (emailInUse(req.email, e.id)) return error(409, { code: '-20502', message: `Email address already in use: ${req.email!.trim()}`, field: 'email' });
      if (Number(ifMatch.replace(/"/g, '')) !== e.version) return error(409, { code: 'CONFLICT', message: 'Record was changed by another user' });

      const oldJobId = e.jobId;
      const oldJobTitle = e.jobTitle;
      Object.assign(e, {
        firstName: req.firstName.trim(),
        middleName: req.middleName ?? null,
        lastName: req.lastName.trim(),
        dateOfBirth: req.dateOfBirth ?? null,
        gender: req.gender ?? null,
        maritalStatus: req.maritalStatus ?? null,
        nationality: req.nationality ?? null,
        email: req.email?.trim() ?? null,
        phoneWork: req.phoneWork ?? null,
        phoneMobile: req.phoneMobile ?? null,
        addressLine1: req.addressLine1 ?? null,
        addressLine2: req.addressLine2 ?? null,
        city: req.city ?? null,
        stateProvince: req.stateProvince ?? null,
        postalCode: req.postalCode ?? null,
        countryCode: req.countryCode ?? null,
        jobId: req.jobId,
        managerEmpId: req.managerEmpId ?? null,
        employmentType: req.employmentType ?? e.employmentType,
        notes: req.notes ?? null,
      });
      if (req.ssn) e.ssn = req.ssn;
      applyReferences(e);
      touch(e, user);
      if (oldJobId !== e.jobId) {
        insertHistory({
          empId: e.id, changeType: 'PROMOTION', effectiveDate: isoDay(0),
          oldDeptId: null, oldDeptName: null, newDeptId: null, newDeptName: null,
          oldJobId, oldJobTitle, newJobId: e.jobId, newJobTitle: e.jobTitle,
          oldManagerId: null, oldManagerName: null, newManagerId: null, newManagerName: null,
          oldSalary: null, newSalary: null, oldLocation: null, newLocation: null, reasonCode: 'JOB_CHANGE', comments: null,
          createdBy: user.userId, createdDate: nowIso(),
        });
      }
      return HttpResponse.json(toDetail(e, true), { headers: { ETag: `"${e.version}"` } });
    }),

    http.post('/api/employees/:id/terminate', async ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:EDIT', true);
      if (!user) return response;
      const req = await body<EmployeeTerminateRequest>(request);
      const invalid = validate('EmployeeTerminateRequest', req);
      if (invalid) return invalid;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (e.employmentStatus === 'TERMINATED') return error(422, { code: '-20005', message: `Employee ${e.id} is already terminated` });
      if (req.effectiveDate < e.hireDate) return validation('effectiveDate', 'Termination date must not precede the hire date');
      const salary = activeSalary(e.id);
      e.employmentStatus = 'TERMINATED';
      e.active = false;
      e.terminationDate = req.effectiveDate;
      e.terminationReason = req.reason;
      touch(e, user);
      closeSalary(e.id, req.effectiveDate);
      insertHistory({
        empId: e.id, changeType: 'TERMINATION', effectiveDate: req.effectiveDate,
        oldDeptId: e.deptId, oldDeptName: e.deptName, newDeptId: null, newDeptName: null,
        oldJobId: e.jobId, oldJobTitle: e.jobTitle, newJobId: null, newJobTitle: null,
        oldManagerId: e.managerEmpId ?? null, oldManagerName: e.managerName ?? null, newManagerId: null, newManagerName: null,
        oldSalary: salary?.baseSalary ?? null, newSalary: null, oldLocation: e.locationCode ?? null, newLocation: null,
        reasonCode: req.reason, comments: req.comments ?? null, createdBy: user.userId, createdDate: nowIso(),
      });
      return HttpResponse.json(toDetail(e, true), { headers: { ETag: `"${e.version}"` } });
    }),

    http.post('/api/employees/:id/transfer', async ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:EDIT', true);
      if (!user) return response;
      const req = await body<EmployeeTransferRequest>(request);
      const invalid = validate('EmployeeTransferRequest', req);
      if (invalid) return invalid;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (e.employmentStatus !== 'ACTIVE') return error(422, { code: '-20012', message: `Cannot transfer non-active employee. Status: ${e.employmentStatus}` });
      const d = dept(req.deptId);
      if (!d?.active) return error(400, { code: '-20003', message: `Invalid or inactive department: ${req.deptId}`, field: 'deptId' });
      if (req.newJobId != null && !job(req.newJobId)?.active) return error(400, { code: '-20011', message: `Invalid or inactive job: ${req.newJobId}`, field: 'newJobId' });
      if (req.newManagerEmpId != null) {
        const me = managerError(e.id, req.newManagerEmpId, 'newManagerEmpId');
        if (me) return me;
      }
      const before = clone(e);
      e.deptId = req.deptId;
      if (req.newJobId != null) e.jobId = req.newJobId;
      if (req.newManagerEmpId != null) e.managerEmpId = req.newManagerEmpId;
      if (req.newLocationCode) e.locationCode = req.newLocationCode;
      applyReferences(e);
      touch(e, user);
      insertHistory({
        empId: e.id, changeType: 'TRANSFER', effectiveDate: req.effectiveDate,
        oldDeptId: before.deptId, oldDeptName: before.deptName, newDeptId: e.deptId, newDeptName: e.deptName,
        oldJobId: before.jobId, oldJobTitle: before.jobTitle, newJobId: e.jobId, newJobTitle: e.jobTitle,
        oldManagerId: before.managerEmpId ?? null, oldManagerName: before.managerName ?? null, newManagerId: e.managerEmpId ?? null, newManagerName: e.managerName ?? null,
        oldSalary: null, newSalary: null, oldLocation: before.locationCode ?? null, newLocation: e.locationCode ?? null,
        reasonCode: req.reasonCode ?? null, comments: req.comments ?? null, createdBy: user.userId, createdDate: nowIso(),
      });
      return HttpResponse.json(toDetail(e, true), { headers: { ETag: `"${e.version}"` } });
    }),

    http.get('/api/employees/:id/salary', ({ request, params }) => {
      const { user, response } = guard(request, null, false);
      if (!user) return response;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (!salaryScope(user, e.id)) return forbidden();
      const s = activeSalary(e.id);
      if (!s) return error(400, { code: '-20104', message: `No active salary record for employee ${e.id}` });
      return HttpResponse.json(clone(s));
    }),

    http.post('/api/employees/:id/salary', async ({ request, params }) => {
      const { user, response } = guard(request, 'PAYROLL:EDIT', true);
      if (!user) return response;
      const req = await body<SalaryChangeRequest>(request);
      const invalid = validate('SalaryChangeRequest', req);
      if (invalid) return invalid;
      if (Number(req.baseSalary) <= 0) return error(400, { code: '-20101', message: `Salary must be positive: ${req.baseSalary}`, field: 'baseSalary' });
      const e = getEmployee(num(params.id));
      if (!e || e.employmentStatus !== 'ACTIVE') return employeeNotFound();
      const current = activeSalary(e.id);
      if (req.effectiveDate < e.hireDate || (current && req.effectiveDate < current.effectiveDate)) {
        return validation('effectiveDate', 'Effective date must not precede the hire date or the current salary record');
      }
      const base = money(Number(req.baseSalary));
      const row: SalaryRecord = insertSalary(
        {
          empId: e.id, effectiveDate: req.effectiveDate, endDate: null, baseSalary: base,
          currencyCode: req.currencyCode ?? 'USD', payFrequency: req.payFrequency ?? 'MONTHLY', salaryBasis: req.salaryBasis ?? 'ANNUAL',
          changeReason: req.changeReason, changePct: pct(current?.baseSalary, base), active: true, outOfGradeBand: outOfBand(e.jobId, base),
          createdBy: user.userId, createdDate: nowIso(),
        },
        isoDay(-1, new Date(`${req.effectiveDate}T00:00:00Z`)),
      );
      insertHistory({
        empId: e.id, changeType: 'SALARY_CHANGE', effectiveDate: req.effectiveDate,
        oldDeptId: null, oldDeptName: null, newDeptId: null, newDeptName: null, oldJobId: null, oldJobTitle: null, newJobId: null, newJobTitle: null,
        oldManagerId: null, oldManagerName: null, newManagerId: null, newManagerName: null,
        oldSalary: current?.baseSalary ?? null, newSalary: base, oldLocation: null, newLocation: null,
        reasonCode: req.changeReason, comments: null, createdBy: user.userId, createdDate: nowIso(),
      });
      return HttpResponse.json(clone(row), { status: 201 });
    }),

    http.get('/api/employees/:id/salary/history', ({ request, params }) => {
      const { user, response } = guard(request, null, false);
      if (!user) return response;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (!salaryScope(user, e.id)) return forbidden();
      return HttpResponse.json(clone(salaryHistory(e.id)));
    }),

    http.get('/api/employees/:id/history', ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', false);
      if (!user) return response;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      const scoped = salaryScope(user, e.id);
      return HttpResponse.json(historyFor(e.id).map((h) => (scoped ? clone(h) : { ...clone(h), oldSalary: null, newSalary: null })));
    }),

    http.get('/api/employees/:id/dependents', ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', false);
      if (!user) return response;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (!subResourceScope(user, e.id)) return forbidden();
      return HttpResponse.json(dependentsFor(e.id).map(toDependent));
    }),

    http.post('/api/employees/:id/dependents', async ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', true);
      if (!user) return response;
      const req = await body<DependentRequest>(request);
      const invalid = validate('DependentRequest', req);
      if (invalid) return invalid;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (!subResourceScope(user, e.id)) return forbidden();
      if (e.employmentStatus === 'TERMINATED') return error(422, { code: '-20503', message: 'Cannot directly reactivate a terminated employee. Use the rehire process.' });
      const row = insertDependent({
        empId: e.id, firstName: req.firstName.trim(), lastName: req.lastName.trim(), relationship: req.relationship,
        dateOfBirth: req.dateOfBirth ?? null, ssn: req.ssn ?? null, ssnLast4: null, benefitsEnrolled: req.benefitsEnrolled ?? false, active: true,
      });
      return HttpResponse.json(toDependent(row), { status: 201 });
    }),

    http.put('/api/employees/:id/dependents/:dependentId', async ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', true);
      if (!user) return response;
      const req = await body<DependentRequest>(request);
      const invalid = validate('DependentRequest', req);
      if (invalid) return invalid;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      const d = getDependent(e.id, num(params.dependentId));
      if (!d) return error(404, { code: 'DEPENDENT_NOT_FOUND', message: 'Dependent not found' });
      if (!subResourceScope(user, e.id)) return forbidden();
      if (e.employmentStatus === 'TERMINATED') return error(422, { code: '-20503', message: 'Cannot directly reactivate a terminated employee. Use the rehire process.' });
      Object.assign(d, {
        firstName: req.firstName.trim(), lastName: req.lastName.trim(), relationship: req.relationship,
        dateOfBirth: req.dateOfBirth ?? null, benefitsEnrolled: req.benefitsEnrolled ?? d.benefitsEnrolled, active: req.active ?? true,
      });
      if (req.ssn) d.ssn = req.ssn;
      return HttpResponse.json(toDependent(d));
    }),

    http.get('/api/employees/:id/contacts', ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', false);
      if (!user) return response;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (!subResourceScope(user, e.id)) return forbidden();
      return HttpResponse.json(clone(contactsFor(e.id)));
    }),

    http.post('/api/employees/:id/contacts', async ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', true);
      if (!user) return response;
      const req = await body<EmergencyContactRequest>(request);
      const invalid = validate('EmergencyContactRequest', req);
      if (invalid) return invalid;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      if (!subResourceScope(user, e.id)) return forbidden();
      if (e.employmentStatus === 'TERMINATED') return error(422, { code: '-20503', message: 'Cannot directly reactivate a terminated employee. Use the rehire process.' });
      const row = insertContact({
        empId: e.id, contactName: req.contactName.trim(), relationship: req.relationship ?? null, phonePrimary: req.phonePrimary,
        phoneSecondary: req.phoneSecondary ?? null, email: req.email ?? null, priorityOrder: req.priorityOrder ?? 1, active: true,
      });
      return HttpResponse.json(clone(row), { status: 201 });
    }),

    http.put('/api/employees/:id/contacts/:contactId', async ({ request, params }) => {
      const { user, response } = guard(request, 'EMPLOYEE:VIEW', true);
      if (!user) return response;
      const req = await body<EmergencyContactRequest>(request);
      const invalid = validate('EmergencyContactRequest', req);
      if (invalid) return invalid;
      const e = getEmployee(num(params.id));
      if (!e) return employeeNotFound();
      const c = getContact(e.id, num(params.contactId));
      if (!c) return error(404, { code: 'CONTACT_NOT_FOUND', message: 'Emergency contact not found' });
      if (!subResourceScope(user, e.id)) return forbidden();
      if (e.employmentStatus === 'TERMINATED') return error(422, { code: '-20503', message: 'Cannot directly reactivate a terminated employee. Use the rehire process.' });
      Object.assign(c, {
        contactName: req.contactName.trim(), relationship: req.relationship ?? null, phonePrimary: req.phonePrimary,
        phoneSecondary: req.phoneSecondary ?? null, email: req.email ?? null, priorityOrder: req.priorityOrder ?? c.priorityOrder, active: req.active ?? true,
      });
      return HttpResponse.json(clone(c));
    }),

    http.delete('/api/employees/*', ({ request }) => {
      if (!authenticate(request)) return unauthorized();
      return error(405, { code: '-20504', message: 'Direct deletion not allowed. Use termination process or set ACTIVE_FLAG to N.' });
    }),
  ];
}
