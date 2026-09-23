import { HttpResponse, http } from 'msw';
import type {
  AccrualRunRequest,
  EmployeeCompensationRow,
  EmployeeDirectoryRow,
  LeaveSummaryRow,
  OrgHierarchyRow,
  PayrollLatestRow,
  PendingApprovalRow,
  ApiError,
  Authority,
  BatchRunResult,
  CarryoverRunRequest,
  Department,
  DepartmentRequest,
  IntegrationFile,
  IntegrationStatus,
  JobGrade,
  JobGradeRequest,
  JobTitle,
  JobTitleRequest,
  LeaveType,
  LeaveTypeRequest,
  Location,
  LocationRequest,
  PageMeta,
  SystemParameter,
  SystemParameterRequest,
  SystemParameterUpdateRequest,
} from '../api/types';
import { getDto, zodFor, type DtoName } from '../validation/schema';
import {
  COMPENSATION_ROWS,
  DIRECTORY_ROWS,
  LEAVE_SUMMARY_ROWS,
  ORG_ROWS,
  PAYROLL_LATEST_ROWS,
  PENDING_ROWS,
  REPORT_AS_OF,
  clone,
  nextId,
  p5,
  uuid,
} from './p5Store';

/**
 * msw implementation of contracts/p5-reporting-decommission/openapi.yaml + error-codes.md
 * (reports, admin reference data, leave batch triggers, audit log, integration). Stands in for
 * `reporting=NEW`. Query / body validation goes through the generated validation-schema.json
 * (`zodFor`) so the mock and the pages agree on one rule source.
 */

interface SessionUser {
  userId: string;
  empId: number;
  username: string;
  roles: Authority[];
}

type Authenticate = (request: Request) => SessionUser | null;

const TRACE = '5a1e7c3d9b2f4e08';

function error(status: number, body: Omit<ApiError, 'traceId'>) {
  return HttpResponse.json<ApiError>({ ...body, traceId: TRACE }, { status });
}

const unauthorized = () => error(401, { code: 'TOKEN_INVALID', message: 'Access token is missing or invalid' });
const forbidden = () => error(403, { code: 'FORBIDDEN', message: 'Access denied' });
const validation = (field: string, message: string) => error(400, { code: 'VALIDATION_FAILED', message, field, details: [{ field, code: 'Invalid', message }] });
const referenceNotFound = (what: string) => error(404, { code: 'REFERENCE_NOT_FOUND', message: `${what} not found` });
const duplicate = (field: string, code: string) => error(409, { code: '-20601', message: `Reference code already exists: ${code}`, field });
const inUse = (message: string) => error(409, { code: '-20602', message });
const valueRule = (field: string, message: string) => error(400, { code: '-20603', message, field });
const notEditable = (p: SystemParameter) => error(422, { code: '-20606', message: `Parameter ${p.paramGroup}.${p.paramCode} is not editable` });

type PathValue = string | readonly string[] | undefined;
const str = (v: PathValue) => (Array.isArray(v) ? v[0] : v) ?? '';
const num = (v: PathValue) => Number(str(v));

async function body<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    return {} as T;
  }
}

/** Parse a query string through the generated DTO; returns the typed object or a `VALIDATION_FAILED` response. */
function parseQuery<T>(request: Request, dto: DtoName): { ok: true; value: T } | { ok: false; response: Response } {
  const raw: Record<string, string> = {};
  new URL(request.url).searchParams.forEach((v, k) => {
    raw[k] = v;
  });
  const fields = getDto(dto).fields;
  const candidate: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw)) {
    const field = fields[k];
    if (!field) continue;
    candidate[k] = field.type === 'boolean' ? v === 'true' : v;
  }
  const parsed = zodFor(dto).safeParse(candidate);
  if (!parsed.success) {
    const issue = parsed.error.issues[0];
    return { ok: false, response: validation(String(issue.path[0] ?? ''), issue.message) };
  }
  return { ok: true, value: parsed.data as T };
}

function parseBody<T>(value: unknown, dto: DtoName): { ok: true; value: T } | { ok: false; response: Response } {
  const parsed = zodFor(dto).safeParse(value ?? {});
  if (!parsed.success) {
    const issue = parsed.error.issues[0];
    return { ok: false, response: validation(String(issue.path[0] ?? ''), issue.message) };
  }
  return { ok: true, value: parsed.data as T };
}

function paginate<T>(rows: T[], page = 0, size = 50): { content: T[]; page: PageMeta } {
  const start = page * size;
  return {
    content: rows.slice(start, start + size),
    page: { page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) },
  };
}

const wantsCsv = (request: Request) => request.url.includes('.csv') || (request.headers.get('accept') ?? '').includes('text/csv');

function csvEscape(v: unknown): string {
  if (v === null || v === undefined) return '';
  const s = typeof v === 'boolean' ? (v ? 'true' : 'false') : String(v);
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** RFC 4180 stream; header row = JSON property names in contract order; `omit` = JSON-only columns. */
function csvResponse(rows: Record<string, unknown>[], columns: string[], filename: string) {
  const lines = [columns.join(','), ...rows.map((r) => columns.map((c) => csvEscape(r[c])).join(','))];
  return new HttpResponse(lines.join('\r\n') + '\r\n', {
    status: 200,
    headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${filename}"` },
  });
}

const DIRECTORY_COLUMNS = ['empId', 'empNumber', 'firstName', 'lastName', 'fullName', 'email', 'phoneWork', 'hireDate', 'tenureYears', 'deptId', 'deptCode', 'deptName', 'costCenter', 'jobId', 'jobCode', 'jobTitle', 'jobFamily', 'gradeId', 'gradeCode', 'gradeName', 'locationCode', 'locationName', 'city', 'stateProvince', 'managerEmpId', 'managerName'];
const ORG_COLUMNS = ['empId', 'empNumber', 'fullName', 'jobTitle', 'deptName', 'managerEmpId', 'managerName', 'orgLevel', 'orgPath', 'isLeaf', 'directReports', 'cycle'];
const COMPENSATION_COLUMNS = ['empId', 'empNumber', 'fullName', 'deptId', 'deptName', 'jobTitle', 'gradeId', 'gradeCode', 'baseSalary', 'currencyCode', 'payFrequency', 'effectiveDate', 'yearsInGrade', 'minSalary', 'maxSalary', 'compaRatio'];
const LEAVE_COLUMNS = ['empId', 'empNumber', 'empName', 'deptName', 'leaveTypeId', 'leaveTypeName', 'calendarYear', 'openingBalance', 'accrued', 'used', 'adjustment', 'pending', 'available', 'utilizationPct'];
const PAYROLL_COLUMNS = ['empId', 'empNumber', 'empName', 'deptName', 'periodId', 'periodName', 'payDate', 'runId', 'runType', 'runStatus', 'grossPay', 'totalTaxes', 'totalDeductions', 'netPay'];
const PENDING_COLUMNS = ['itemType', 'itemId', 'empId', 'empNumber', 'empName', 'deptName', 'approverEmpId', 'approverName', 'submittedDate', 'daysPending', 'detail'];
const AUDIT_COLUMNS = ['auditId', 'tableName', 'recordId', 'actionType', 'oldValues', 'newValues', 'changedBy', 'changedDate', 'ipAddress', 'sessionId'];

const money = (n: number) => n.toFixed(2);
const sum = (values: string[]) => values.reduce((a, v) => a + Number(v), 0);

const CODE_RE = /^[A-Z0-9_-]+$/;

export function createP5Handlers(authenticate: Authenticate) {
  const guard = (request: Request, ...required: Authority[]): { user: SessionUser } | { response: Response } => {
    const user = authenticate(request);
    if (!user) return { response: unauthorized() };
    if (!required.every((a) => user.roles.includes(a))) return { response: forbidden() };
    return { user };
  };

  const activeFilter = <T extends { activeFlag: boolean }>(request: Request, rows: T[]) => {
    const active = new URL(request.url).searchParams.get('active');
    if (active === 'true') return rows.filter((r) => r.activeFlag);
    if (active === 'false') return rows.filter((r) => !r.activeFlag);
    return rows;
  };

  const stamp = (user: SessionUser) => ({ modifiedBy: user.username, modifiedDate: new Date().toISOString() });
  const created = (user: SessionUser) => ({ createdBy: user.username, createdDate: new Date().toISOString(), modifiedBy: null, modifiedDate: null });

  const departmentRefOk = (id: number | null | undefined) => id == null || p5.departments.some((d) => d.deptId === id && d.activeFlag);
  const locationRefOk = (code: string | null | undefined) => code == null || p5.locations.some((l) => l.locationCode === code && l.activeFlag);
  const gradeRefOk = (id: number) => p5.grades.some((g) => g.gradeId === id && g.activeFlag);
  const isCycle = (deptId: number, parentId: number | null | undefined) => {
    let cursor = parentId ?? null;
    const seen = new Set<number>();
    while (cursor != null) {
      if (cursor === deptId || seen.has(cursor)) return true;
      seen.add(cursor);
      cursor = p5.departments.find((d) => d.deptId === cursor)?.parentDeptId ?? null;
    }
    return false;
  };

  // --- reports -------------------------------------------------------------------
  const reportHandler = <Q extends { page?: number; size?: number }, R extends object>(
    path: string,
    dto: DtoName,
    required: Authority[] | ((user: SessionUser, request: Request) => boolean),
    rows: (q: Q, user: SessionUser) => R[],
    columns: string[],
    filename: string,
    extra?: (q: Q, all: R[]) => Record<string, unknown>,
  ) =>
    [path, `${path}.csv`].map((p) =>
      http.get(p, ({ request }) => {
        const auth = typeof required === 'function' ? guard(request) : guard(request, ...required);
        if ('response' in auth) return auth.response;
        if (typeof required === 'function' && !required(auth.user, request)) return forbidden();
        const accept = request.headers.get('accept') ?? 'application/json';
        if (!p.endsWith('.csv') && !/application\/json|text\/csv|\*\/\*/.test(accept)) {
          return error(406, { code: 'NOT_ACCEPTABLE', message: 'Supported representations are application/json and text/csv' });
        }
        const q = parseQuery<Q>(request, dto);
        if (!q.ok) return q.response;
        const all = rows(q.value, auth.user);
        if (wantsCsv(request)) return csvResponse(all as unknown as Record<string, unknown>[], columns, filename);
        const paged = paginate(all, q.value.page ?? 0, q.value.size ?? 50);
        return HttpResponse.json({ asOf: REPORT_AS_OF, ...paged, ...(extra ? extra(q.value, all) : {}) });
      }),
    );

  const handlers = [
    ...reportHandler<{ asOf?: string; deptId?: number; locationCode?: string; page?: number; size?: number }, EmployeeDirectoryRow>(
      '/api/reports/employee-directory',
      'EmployeeDirectoryQuery',
      ['REPORTS:VIEW'],
      (q) => DIRECTORY_ROWS.filter((r) => (q.deptId ? r.deptId === q.deptId : true) && (q.locationCode ? r.locationCode === q.locationCode : true)).map((r) => ({ ...r })),
      DIRECTORY_COLUMNS,
      `employee-directory-${REPORT_AS_OF}.csv`,
      (_q, all) => {
        const byDept = new Map<number, { deptId: number; deptCode: string; deptName: string; locationCode: string | null; headcount: number; tenure: number }>();
        for (const row of all) {
          const e = byDept.get(row.deptId) ?? { deptId: row.deptId, deptCode: row.deptCode, deptName: row.deptName, locationCode: row.locationCode ?? null, headcount: 0, tenure: 0 };
          e.headcount += 1;
          e.tenure += Number(row.tenureYears);
          byDept.set(row.deptId, e);
        }
        return {
          summary: {
            totalHeadcount: all.length,
            headcountByDepartment: [...byDept.values()].map(({ tenure, ...d }) => ({ ...d, avgTenureYears: (tenure / d.headcount).toFixed(1) })),
          },
        };
      },
    ),
    ...reportHandler<{ asOf?: string; rootEmpId?: number; maxLevel?: number; page?: number; size?: number }, OrgHierarchyRow>(
      '/api/reports/org-hierarchy',
      'OrgHierarchyQuery',
      ['REPORTS:VIEW'],
      (q) => {
        let rows = ORG_ROWS;
        if (q.rootEmpId) {
          const root = ORG_ROWS.find((r) => r.empId === q.rootEmpId);
          if (!root) return [];
          rows = ORG_ROWS.filter((r) => r.orgPath.startsWith(root.orgPath)).map((r) => ({ ...r, orgLevel: r.orgLevel - root.orgLevel + 1, orgPath: r.orgPath.slice(root.orgPath.length - root.fullName.length) }));
        }
        return rows.filter((r) => (q.maxLevel ? r.orgLevel <= q.maxLevel : true)).map((r) => ({ ...r }));
      },
      ORG_COLUMNS,
      `org-hierarchy-${REPORT_AS_OF}.csv`,
    ),
    ...reportHandler<{ asOf?: string; deptId?: number; gradeId?: number; page?: number; size?: number }, EmployeeCompensationRow>(
      '/api/reports/employee-compensation',
      'EmployeeCompensationQuery',
      ['REPORTS:VIEW', 'PAYROLL:VIEW'],
      (q) => COMPENSATION_ROWS.filter((r) => (q.deptId ? r.deptId === q.deptId : true) && (q.gradeId ? r.gradeId === q.gradeId : true)).map((r) => ({ ...r })),
      COMPENSATION_COLUMNS,
      `employee-compensation-${REPORT_AS_OF}.csv`,
      (_q, all) => {
        const byDept = new Map<number, { deptId: number; deptName: string; salaries: number[] }>();
        for (const row of all) {
          const e = byDept.get(row.deptId ?? 0) ?? { deptId: row.deptId ?? 0, deptName: row.deptName, salaries: [] };
          e.salaries.push(Number(row.baseSalary));
          byDept.set(row.deptId ?? 0, e);
        }
        return {
          summary: {
            byDepartment: [...byDept.values()].map((d) => ({
              deptId: d.deptId,
              deptName: d.deptName,
              headcount: d.salaries.length,
              avgSalary: money(d.salaries.reduce((a, b) => a + b, 0) / d.salaries.length),
              minSalary: money(Math.min(...d.salaries)),
              maxSalary: money(Math.max(...d.salaries)),
              totalPayroll: money(d.salaries.reduce((a, b) => a + b, 0)),
            })),
          },
        };
      },
    ),
    ...reportHandler<{ asOf?: string; year?: number; deptId?: number; leaveTypeId?: number; page?: number; size?: number }, LeaveSummaryRow>(
      '/api/reports/leave-summary',
      'LeaveSummaryQuery',
      ['REPORTS:VIEW'],
      (q) =>
        LEAVE_SUMMARY_ROWS.filter(
          (r) => r.calendarYear === (q.year ?? 2024) && (q.leaveTypeId ? r.leaveTypeId === q.leaveTypeId : true) && (q.deptId ? DIRECTORY_ROWS.find((d) => d.empId === r.empId)?.deptId === q.deptId : true),
        ).map((r) => ({ ...r })),
      LEAVE_COLUMNS,
      `leave-summary-${REPORT_AS_OF}.csv`,
      (q, all) => {
        const byType = new Map<number, { leaveTypeId: number; leaveTypeName: string; employees: number; accrued: string[]; used: string[]; pct: number[] }>();
        for (const row of all) {
          const e = byType.get(row.leaveTypeId) ?? { leaveTypeId: row.leaveTypeId, leaveTypeName: row.leaveTypeName, employees: 0, accrued: [], used: [], pct: [] };
          e.employees += 1;
          e.accrued.push(row.accrued);
          e.used.push(row.used);
          if (row.utilizationPct !== null) e.pct.push(Number(row.utilizationPct));
          byType.set(row.leaveTypeId, e);
        }
        return {
          year: q.year ?? 2024,
          summary: {
            byLeaveType: [...byType.values()].map((t) => ({
              leaveTypeId: t.leaveTypeId,
              leaveTypeName: t.leaveTypeName,
              employees: t.employees,
              totalAccrued: money(sum(t.accrued)),
              totalUsed: money(sum(t.used)),
              avgUtilizationPct: t.pct.length ? (t.pct.reduce((a, b) => a + b, 0) / t.pct.length).toFixed(1) : null,
            })),
          },
        };
      },
    ),
    ...reportHandler<{ periodId?: number; deptId?: number; page?: number; size?: number }, PayrollLatestRow>(
      '/api/reports/payroll-latest',
      'PayrollLatestQuery',
      ['REPORTS:VIEW', 'PAYROLL:VIEW'],
      (q) => PAYROLL_LATEST_ROWS.filter((r) => (q.periodId ? r.periodId === q.periodId : true) && (q.deptId ? DIRECTORY_ROWS.find((d) => d.empId === r.empId)?.deptId === q.deptId : true)).map((r) => ({ ...r })),
      PAYROLL_COLUMNS,
      'payroll-latest.csv',
      (q, all) => {
        const rows = all;
        const net = sum(rows.map((r) => r.netPay));
        return {
          summary: {
            periodId: q.periodId ?? rows[0]?.periodId ?? 0,
            employeeCount: rows.length,
            totalGross: money(sum(rows.map((r) => r.grossPay))),
            totalTaxes: money(sum(rows.map((r) => r.totalTaxes))),
            totalDeductions: money(sum(rows.map((r) => r.totalDeductions))),
            totalNet: money(net),
            avgNet: money(rows.length ? net / rows.length : 0),
          },
        };
      },
    ),
    ...reportHandler<{ asOf?: string; itemType?: 'LEAVE' | 'REVIEW'; mine?: boolean; deptId?: number; page?: number; size?: number }, PendingApprovalRow>(
      '/api/reports/pending-approvals',
      'PendingApprovalsQuery',
      (user, request) => user.roles.includes('REPORTS:VIEW') || new URL(request.url).searchParams.get('mine') === 'true',
      (q, user) =>
        PENDING_ROWS.filter(
          (r) => (q.itemType ? r.itemType === q.itemType : true) && (q.mine ? r.approverEmpId === user.empId : true) && (q.deptId ? DIRECTORY_ROWS.find((d) => d.empId === r.empId)?.deptId === q.deptId : true),
        ).map((r) => ({ ...r })),
      PENDING_COLUMNS,
      `pending-approvals-${REPORT_AS_OF}.csv`,
      (_q, all) => ({ summary: { leave: all.filter((r) => r.itemType === 'LEAVE').length, review: all.filter((r) => r.itemType === 'REVIEW').length } }),
    ),

    // --- admin: departments -----------------------------------------------------------
    http.get('/api/admin/departments', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      return HttpResponse.json(clone(activeFilter(request, p5.departments).slice().sort((a, b) => a.deptCode.localeCompare(b.deptCode))));
    }),
    http.post('/api/admin/departments', async ({ request }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<DepartmentRequest>(await body(request), 'DepartmentRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (p5.departments.some((d) => d.deptCode === b.deptCode)) return duplicate('deptCode', b.deptCode);
      if (!departmentRefOk(b.parentDeptId)) return error(400, { code: '-20003', message: `Invalid or inactive department: ${b.parentDeptId}`, field: 'parentDeptId' });
      if (!locationRefOk(b.locationCode)) return error(400, { code: '-20604', message: `Invalid or inactive location: ${b.locationCode}`, field: 'locationCode' });
      if (b.managerEmpId != null && !DIRECTORY_ROWS.some((e) => e.empId === b.managerEmpId)) return error(400, { code: '-20001', message: `Employee not found or not active: ${b.managerEmpId}`, field: 'managerEmpId' });
      const dept: Department = {
        deptId: nextId(),
        ...b,
        parentDeptId: b.parentDeptId ?? null,
        parentDeptName: p5.departments.find((d) => d.deptId === b.parentDeptId)?.deptName ?? null,
        costCenter: b.costCenter ?? null,
        managerEmpId: b.managerEmpId ?? null,
        managerName: DIRECTORY_ROWS.find((e) => e.empId === b.managerEmpId)?.fullName ?? null,
        locationCode: b.locationCode ?? null,
        activeFlag: b.activeFlag ?? true,
        activeEmployees: 0,
        ...created(auth.user),
      };
      p5.departments.push(dept);
      return HttpResponse.json(clone(dept), { status: 201, headers: { Location: `/api/admin/departments/${dept.deptId}` } });
    }),
    http.get('/api/admin/departments/:deptId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const dept = p5.departments.find((d) => d.deptId === num(params.deptId));
      return dept ? HttpResponse.json(clone(dept)) : referenceNotFound('Department');
    }),
    http.put('/api/admin/departments/:deptId', async ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const dept = p5.departments.find((d) => d.deptId === num(params.deptId));
      if (!dept) return referenceNotFound('Department');
      const parsed = parseBody<DepartmentRequest>(await body(request), 'DepartmentRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (b.deptCode !== dept.deptCode) return duplicate('deptCode', b.deptCode);
      if (!departmentRefOk(b.parentDeptId)) return error(400, { code: '-20003', message: `Invalid or inactive department: ${b.parentDeptId}`, field: 'parentDeptId' });
      if (isCycle(dept.deptId, b.parentDeptId)) return error(400, { code: '-20605', message: 'Department hierarchy would form a cycle', field: 'parentDeptId' });
      if (!locationRefOk(b.locationCode)) return error(400, { code: '-20604', message: `Invalid or inactive location: ${b.locationCode}`, field: 'locationCode' });
      Object.assign(dept, b, {
        parentDeptName: p5.departments.find((d) => d.deptId === b.parentDeptId)?.deptName ?? null,
        managerName: DIRECTORY_ROWS.find((e) => e.empId === b.managerEmpId)?.fullName ?? null,
        activeFlag: b.activeFlag ?? true,
        ...stamp(auth.user),
      });
      return HttpResponse.json(clone(dept));
    }),
    http.delete('/api/admin/departments/:deptId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const dept = p5.departments.find((d) => d.deptId === num(params.deptId));
      if (!dept) return referenceNotFound('Department');
      if ((dept.activeEmployees ?? 0) > 0) return inUse(`Department ${dept.deptCode} has ${dept.activeEmployees} active employees`);
      dept.activeFlag = false;
      Object.assign(dept, stamp(auth.user));
      return new HttpResponse(null, { status: 204 });
    }),

    // --- admin: job grades ------------------------------------------------------------
    http.get('/api/admin/job-grades', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      return HttpResponse.json(clone(activeFilter(request, p5.grades)));
    }),
    http.post('/api/admin/job-grades', async ({ request }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const raw = await body<JobGradeRequest>(request);
      const parsed = parseBody<{ gradeCode: string; gradeName: string; minSalary: number; maxSalary: number; overtimeEligible?: boolean; activeFlag?: boolean }>(raw, 'JobGradeRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (p5.grades.some((g) => g.gradeCode === b.gradeCode)) return duplicate('gradeCode', b.gradeCode);
      if (b.maxSalary < b.minSalary) return valueRule('maxSalary', 'Maximum salary must be greater than or equal to minimum salary');
      const grade: JobGrade = { gradeId: nextId(), gradeCode: b.gradeCode, gradeName: b.gradeName, minSalary: money(b.minSalary), maxSalary: money(b.maxSalary), overtimeEligible: b.overtimeEligible ?? false, activeFlag: b.activeFlag ?? true, activeJobTitles: 0, ...created(auth.user) };
      p5.grades.push(grade);
      return HttpResponse.json(clone(grade), { status: 201, headers: { Location: `/api/admin/job-grades/${grade.gradeId}` } });
    }),
    http.get('/api/admin/job-grades/:gradeId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const g = p5.grades.find((x) => x.gradeId === num(params.gradeId));
      return g ? HttpResponse.json(clone(g)) : referenceNotFound('Job grade');
    }),
    http.put('/api/admin/job-grades/:gradeId', async ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const g = p5.grades.find((x) => x.gradeId === num(params.gradeId));
      if (!g) return referenceNotFound('Job grade');
      const parsed = parseBody<{ gradeCode: string; gradeName: string; minSalary: number; maxSalary: number; overtimeEligible?: boolean; activeFlag?: boolean }>(await body(request), 'JobGradeRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (b.gradeCode !== g.gradeCode) return duplicate('gradeCode', b.gradeCode);
      if (b.maxSalary < b.minSalary) return valueRule('maxSalary', 'Maximum salary must be greater than or equal to minimum salary');
      Object.assign(g, { gradeName: b.gradeName, minSalary: money(b.minSalary), maxSalary: money(b.maxSalary), overtimeEligible: b.overtimeEligible ?? false, activeFlag: b.activeFlag ?? true, ...stamp(auth.user) });
      return HttpResponse.json(clone(g));
    }),
    http.delete('/api/admin/job-grades/:gradeId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const g = p5.grades.find((x) => x.gradeId === num(params.gradeId));
      if (!g) return referenceNotFound('Job grade');
      if ((g.activeJobTitles ?? 0) > 0) return inUse(`Job grade ${g.gradeCode} is used by ${g.activeJobTitles} active job titles`);
      g.activeFlag = false;
      Object.assign(g, stamp(auth.user));
      return new HttpResponse(null, { status: 204 });
    }),

    // --- admin: job titles ------------------------------------------------------------
    http.get('/api/admin/job-titles', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      return HttpResponse.json(clone(activeFilter(request, p5.jobTitles).slice().sort((a, b) => a.jobCode.localeCompare(b.jobCode))));
    }),
    http.post('/api/admin/job-titles', async ({ request }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<JobTitleRequest>(await body(request), 'JobTitleRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (p5.jobTitles.some((j) => j.jobCode === b.jobCode)) return duplicate('jobCode', b.jobCode);
      if (!gradeRefOk(b.gradeId)) return error(400, { code: '-20604', message: `Invalid or inactive grade: ${b.gradeId}`, field: 'gradeId' });
      const grade = p5.grades.find((g) => g.gradeId === b.gradeId)!;
      const job: JobTitle = { jobId: nextId(), ...b, jobFamily: b.jobFamily ?? null, eeoCategory: b.eeoCategory ?? null, flsaStatus: b.flsaStatus ?? 'EXEMPT', activeFlag: b.activeFlag ?? true, gradeCode: grade.gradeCode, activeEmployees: 0, ...created(auth.user) };
      grade.activeJobTitles = (grade.activeJobTitles ?? 0) + 1;
      p5.jobTitles.push(job);
      return HttpResponse.json(clone(job), { status: 201, headers: { Location: `/api/admin/job-titles/${job.jobId}` } });
    }),
    http.get('/api/admin/job-titles/:jobId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const j = p5.jobTitles.find((x) => x.jobId === num(params.jobId));
      return j ? HttpResponse.json(clone(j)) : referenceNotFound('Job title');
    }),
    http.put('/api/admin/job-titles/:jobId', async ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const j = p5.jobTitles.find((x) => x.jobId === num(params.jobId));
      if (!j) return referenceNotFound('Job title');
      const parsed = parseBody<JobTitleRequest>(await body(request), 'JobTitleRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (b.jobCode !== j.jobCode) return duplicate('jobCode', b.jobCode);
      if (!gradeRefOk(b.gradeId)) return error(400, { code: '-20604', message: `Invalid or inactive grade: ${b.gradeId}`, field: 'gradeId' });
      Object.assign(j, b, { gradeCode: p5.grades.find((g) => g.gradeId === b.gradeId)!.gradeCode, flsaStatus: b.flsaStatus ?? 'EXEMPT', activeFlag: b.activeFlag ?? true, ...stamp(auth.user) });
      return HttpResponse.json(clone(j));
    }),
    http.delete('/api/admin/job-titles/:jobId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const j = p5.jobTitles.find((x) => x.jobId === num(params.jobId));
      if (!j) return referenceNotFound('Job title');
      if ((j.activeEmployees ?? 0) > 0) return inUse(`Job ${j.jobCode} is held by ${j.activeEmployees} active employees`);
      j.activeFlag = false;
      Object.assign(j, stamp(auth.user));
      return new HttpResponse(null, { status: 204 });
    }),

    // --- admin: locations -------------------------------------------------------------
    http.get('/api/admin/locations', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      return HttpResponse.json(clone(activeFilter(request, p5.locations).slice().sort((a, b) => a.locationCode.localeCompare(b.locationCode))));
    }),
    http.post('/api/admin/locations', async ({ request }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<LocationRequest>(await body(request), 'LocationRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (p5.locations.some((l) => l.locationCode === b.locationCode)) return duplicate('locationCode', b.locationCode);
      const loc: Location = { ...b, timezone: b.timezone ?? 'America/New_York', activeFlag: b.activeFlag ?? true, activeEmployees: 0, activeDepartments: 0, ...created(auth.user) };
      p5.locations.push(loc);
      return HttpResponse.json(clone(loc), { status: 201, headers: { Location: `/api/admin/locations/${loc.locationCode}` } });
    }),
    http.get('/api/admin/locations/:locationCode', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      if (!CODE_RE.test(str(params.locationCode))) return validation('locationCode', 'Invalid location code');
      const l = p5.locations.find((x) => x.locationCode === str(params.locationCode));
      return l ? HttpResponse.json(clone(l)) : referenceNotFound('Location');
    }),
    http.put('/api/admin/locations/:locationCode', async ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const l = p5.locations.find((x) => x.locationCode === str(params.locationCode));
      if (!l) return referenceNotFound('Location');
      const parsed = parseBody<LocationRequest>(await body(request), 'LocationRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (b.locationCode !== l.locationCode) return duplicate('locationCode', b.locationCode);
      Object.assign(l, b, { timezone: b.timezone ?? l.timezone, activeFlag: b.activeFlag ?? true, ...stamp(auth.user) });
      return HttpResponse.json(clone(l));
    }),
    http.delete('/api/admin/locations/:locationCode', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const l = p5.locations.find((x) => x.locationCode === str(params.locationCode));
      if (!l) return referenceNotFound('Location');
      if ((l.activeEmployees ?? 0) + (l.activeDepartments ?? 0) > 0) return inUse(`Location ${l.locationCode} has ${l.activeEmployees} active employees and ${l.activeDepartments} active departments`);
      l.activeFlag = false;
      Object.assign(l, stamp(auth.user));
      return new HttpResponse(null, { status: 204 });
    }),

    // --- admin: leave types -----------------------------------------------------------
    http.get('/api/admin/leave-types', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      return HttpResponse.json(clone(activeFilter(request, p5.leaveTypes).slice().sort((a, b) => a.leaveTypeCode.localeCompare(b.leaveTypeCode))));
    }),
    http.post('/api/admin/leave-types', async ({ request }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<Record<string, unknown>>(await body(request), 'LeaveTypeRequest');
      if (!parsed.ok) return parsed.response;
      const lt = leaveTypeFromRequest(parsed.value);
      if (p5.leaveTypes.some((x) => x.leaveTypeCode === lt.leaveTypeCode)) return duplicate('leaveTypeCode', lt.leaveTypeCode);
      const ruleFailure = leaveTypeRule(lt);
      if (ruleFailure) return ruleFailure;
      const row: LeaveType = { leaveTypeId: nextId(), ...lt, pendingRequests: 0, ...created(auth.user) };
      p5.leaveTypes.push(row);
      return HttpResponse.json(clone(row), { status: 201, headers: { Location: `/api/admin/leave-types/${row.leaveTypeId}` } });
    }),
    http.get('/api/admin/leave-types/:leaveTypeId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const lt = p5.leaveTypes.find((x) => x.leaveTypeId === num(params.leaveTypeId));
      return lt ? HttpResponse.json(clone(lt)) : referenceNotFound('Leave type');
    }),
    http.put('/api/admin/leave-types/:leaveTypeId', async ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const existing = p5.leaveTypes.find((x) => x.leaveTypeId === num(params.leaveTypeId));
      if (!existing) return referenceNotFound('Leave type');
      const parsed = parseBody<Record<string, unknown>>(await body(request), 'LeaveTypeRequest');
      if (!parsed.ok) return parsed.response;
      const lt = leaveTypeFromRequest(parsed.value);
      if (lt.leaveTypeCode !== existing.leaveTypeCode) return duplicate('leaveTypeCode', lt.leaveTypeCode);
      const ruleFailure = leaveTypeRule(lt);
      if (ruleFailure) return ruleFailure;
      Object.assign(existing, lt, stamp(auth.user));
      return HttpResponse.json(clone(existing));
    }),
    http.delete('/api/admin/leave-types/:leaveTypeId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const lt = p5.leaveTypes.find((x) => x.leaveTypeId === num(params.leaveTypeId));
      if (!lt) return referenceNotFound('Leave type');
      if ((lt.pendingRequests ?? 0) > 0) return inUse(`Leave type ${lt.leaveTypeCode} has ${lt.pendingRequests} pending requests`);
      lt.activeFlag = false;
      Object.assign(lt, stamp(auth.user));
      return new HttpResponse(null, { status: 204 });
    }),

    // --- admin: system parameters -----------------------------------------------------
    http.get('/api/admin/system-parameters', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const group = new URL(request.url).searchParams.get('group');
      if (group !== null && !/^[A-Z][A-Z0-9_]*$/.test(group)) return validation('group', 'Invalid parameter group');
      const rows = p5.parameters.filter((p) => (group ? p.paramGroup === group : true)).sort((a, b) => a.paramGroup.localeCompare(b.paramGroup) || a.paramCode.localeCompare(b.paramCode));
      return HttpResponse.json(clone(rows));
    }),
    http.post('/api/admin/system-parameters', async ({ request }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<SystemParameterRequest>(await body(request), 'SystemParameterRequest');
      if (!parsed.ok) return parsed.response;
      const b = parsed.value;
      if (p5.parameters.some((p) => p.paramGroup === b.paramGroup && p.paramCode === b.paramCode)) return duplicate('paramCode', `${b.paramGroup}.${b.paramCode}`);
      const bad = paramValueRule(b.dataType, b.paramValue);
      if (bad) return bad;
      const row: SystemParameter = { paramId: nextId(), ...b, paramDescription: b.paramDescription ?? null, editableFlag: b.editableFlag ?? true, createdBy: auth.user.username, createdDate: new Date().toISOString(), modifiedBy: null, modifiedDate: null };
      p5.parameters.push(row);
      return HttpResponse.json(clone(row), { status: 201, headers: { Location: `/api/admin/system-parameters/${row.paramId}` } });
    }),
    http.get('/api/admin/system-parameters/:paramId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const p = p5.parameters.find((x) => x.paramId === num(params.paramId));
      return p ? HttpResponse.json(clone(p)) : referenceNotFound('Parameter');
    }),
    http.put('/api/admin/system-parameters/:paramId', async ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const p = p5.parameters.find((x) => x.paramId === num(params.paramId));
      if (!p) return referenceNotFound('Parameter');
      const parsed = parseBody<SystemParameterUpdateRequest>(await body(request), 'SystemParameterUpdateRequest');
      if (!parsed.ok) return parsed.response;
      if (!p.editableFlag) return notEditable(p);
      const bad = paramValueRule(p.dataType, parsed.value.paramValue);
      if (bad) return bad;
      Object.assign(p, { paramValue: parsed.value.paramValue, paramDescription: parsed.value.paramDescription ?? p.paramDescription, ...stamp(auth.user) });
      return HttpResponse.json(clone(p));
    }),
    http.delete('/api/admin/system-parameters/:paramId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:EDIT');
      if ('response' in auth) return auth.response;
      const p = p5.parameters.find((x) => x.paramId === num(params.paramId));
      if (!p) return referenceNotFound('Parameter');
      if (!p.editableFlag) return notEditable(p);
      p5.parameters = p5.parameters.filter((x) => x !== p);
      return new HttpResponse(null, { status: 204 });
    }),

    // --- admin: leave batch triggers --------------------------------------------------
    http.post('/api/admin/leave/accrual', async ({ request }) => {
      const auth = guard(request, 'LEAVE:ADMIN');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<AccrualRunRequest>(await body(request), 'AccrualRunRequest');
      if (!parsed.ok) return parsed.response;
      const accrualDate = parsed.value.accrualDate ?? new Date().toISOString().slice(0, 10);
      if (p5.jobs.some((j) => j.jobType === 'ACCRUAL' && j.status === 'RUNNING' && j.message === accrualDate)) {
        return error(409, { code: '-20702', message: `Accrual for ${accrualDate} is already running`, field: 'accrualDate' });
      }
      const rerun = p5.jobs.some((j) => j.jobType === 'ACCRUAL' && j.message === accrualDate);
      const job = startJob('ACCRUAL', auth.user, accrualDate, rerun);
      return HttpResponse.json(clone(job), { status: 202, headers: { Location: `/api/admin/leave/jobs/${job.jobId}` } });
    }),
    http.post('/api/admin/leave/carryover', async ({ request }) => {
      const auth = guard(request, 'LEAVE:ADMIN');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<CarryoverRunRequest>(await body(request), 'CarryoverRunRequest');
      if (!parsed.ok) return parsed.response;
      const key = String(parsed.value.year);
      if (p5.jobs.some((j) => j.jobType === 'CARRYOVER' && j.status === 'RUNNING' && j.message === key)) {
        return error(409, { code: '-20702', message: `Carryover for ${key} is already running`, field: 'year' });
      }
      const rerun = p5.jobs.some((j) => j.jobType === 'CARRYOVER' && j.message === key);
      const job = startJob('CARRYOVER', auth.user, key, rerun);
      return HttpResponse.json(clone(job), { status: 202, headers: { Location: `/api/admin/leave/jobs/${job.jobId}` } });
    }),
    http.get('/api/admin/leave/jobs/:jobId', ({ request, params }) => {
      const auth = guard(request, 'LEAVE:ADMIN');
      if ('response' in auth) return auth.response;
      const job = p5.jobs.find((j) => j.jobId === str(params.jobId));
      if (!job) return error(404, { code: 'JOB_NOT_FOUND', message: 'Job not found' });
      // The simulated batch completes on the first poll.
      if (job.status === 'RUNNING') {
        job.status = 'COMPLETED';
        job.finishedAt = new Date().toISOString();
      }
      return HttpResponse.json(clone(job));
    }),

    // --- admin: audit log -------------------------------------------------------------
    http.get('/api/admin/audit-log', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const q = parseQuery<{ tableName?: string; recordId?: number; actionType?: string; changedBy?: string; from?: string; to?: string; page?: number; size?: number }>(request, 'AuditLogSearchQuery');
      if (!q.ok) return q.response;
      const f = q.value;
      const rows = p5.audit
        .filter(
          (r) =>
            (f.tableName ? r.tableName === f.tableName : true) &&
            (f.recordId ? r.recordId === f.recordId : true) &&
            (f.actionType ? r.actionType === f.actionType : true) &&
            (f.changedBy ? r.changedBy === f.changedBy : true) &&
            (f.from ? r.changedDate.slice(0, 10) >= f.from : true) &&
            (f.to ? r.changedDate.slice(0, 10) <= f.to : true),
        )
        .sort((a, b) => b.changedDate.localeCompare(a.changedDate) || b.auditId - a.auditId);
      if (wantsCsv(request)) return csvResponse(rows.map((r) => ({ ...r })), AUDIT_COLUMNS, 'audit-log.csv');
      return HttpResponse.json(paginate(rows, f.page ?? 0, f.size ?? 50));
    }),

    // --- integration ------------------------------------------------------------------
    http.post('/api/integration/gl-feed', async ({ request }) => {
      const auth = guard(request, 'PAYROLL:APPROVE');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<{ runId: number }>(await body(request), 'GlFeedRequest');
      if (!parsed.ok) return parsed.response;
      const run = PAYROLL_LATEST_ROWS.find((r) => r.runId === parsed.value.runId);
      if (!run) return error(404, { code: 'RUN_NOT_FOUND', message: `Payroll run not found: ${parsed.value.runId}`, field: 'runId' });
      const file = newFile('GL_JOURNAL', `GL_JOURNAL_${run.runId}_${run.payDate.replace(/-/g, '')}.txt`, String(run.runId), auth.user);
      return HttpResponse.json(clone(file), { status: 201, headers: { Location: `/api/integration/files/${file.fileId}` } });
    }),
    http.post('/api/integration/benefits-feed', async ({ request }) => {
      const auth = guard(request, 'ADMIN:EDIT', 'EMPLOYEE:VIEW');
      if ('response' in auth) return auth.response;
      const parsed = parseBody<{ effectiveDate?: string }>(await body(request), 'BenefitsFeedRequest');
      if (!parsed.ok) return parsed.response;
      const d = parsed.value.effectiveDate ?? new Date().toISOString().slice(0, 10);
      const file = newFile('BENEFITS_FEED', `BENEFITS_${d.replace(/-/g, '')}.txt`, d, auth.user);
      return HttpResponse.json(clone(file), { status: 201, headers: { Location: `/api/integration/files/${file.fileId}` } });
    }),
    http.get('/api/integration/files', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const q = parseQuery<{ feed?: string; status?: string; from?: string; to?: string; page?: number; size?: number }>(request, 'IntegrationFileListQuery');
      if (!q.ok) return q.response;
      const f = q.value;
      const rows = p5.files.filter((r) => (f.feed ? r.feed === f.feed : true) && (f.status ? r.status === f.status : true)).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      return HttpResponse.json(paginate(rows, f.page ?? 0, f.size ?? 50));
    }),
    http.get('/api/integration/files/:fileId', ({ request, params }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const file = p5.files.find((f) => f.fileId === str(params.fileId));
      return file ? HttpResponse.json(clone(file)) : error(404, { code: 'FILE_NOT_FOUND', message: 'Integration file not found' });
    }),
    http.get('/api/integration/status', ({ request }) => {
      const auth = guard(request, 'ADMIN:VIEW');
      if ('response' in auth) return auth.response;
      const feeds: IntegrationStatus['feed'][] = ['GL_JOURNAL', 'BENEFITS_FEED', 'TIME_ATTENDANCE'];
      return HttpResponse.json(
        feeds.map((feed): IntegrationStatus => {
          const last = p5.files.filter((f) => f.feed === feed).sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
          return last ? { feed, status: last.status, lastRunAt: last.createdAt, lastFileId: last.fileId, lastRunBy: last.createdBy, message: last.message ?? null } : { feed, status: 'NEVER_RUN', lastRunAt: null, lastFileId: null, lastRunBy: null, message: null };
        }),
      );
    }),
  ];

  return handlers;

  function startJob(jobType: BatchRunResult['jobType'], user: SessionUser, key: string, rerun: boolean): BatchRunResult {
    const job: BatchRunResult = {
      jobId: uuid(),
      jobType,
      status: 'RUNNING',
      processed: rerun ? 0 : 3,
      skipped: rerun ? 3 : 0,
      failed: 0,
      startedAt: new Date().toISOString(),
      finishedAt: null,
      startedBy: user.username,
      message: key,
    };
    p5.jobs.push(job);
    return job;
  }

  function newFile(feed: IntegrationFile['feed'], fileName: string, sourceRef: string, user: SessionUser): IntegrationFile {
    const fileId = uuid();
    const file: IntegrationFile = {
      fileId,
      feed,
      fileName,
      status: 'SUCCESS',
      sizeBytes: 256,
      sha256: 'b'.repeat(64),
      recordCount: 4,
      sourceRef,
      storageKey: `${feed}/2024/06/${fileId}-${fileName}`,
      contentUrl: `/api/integration/files/${fileId}/content`,
      createdBy: user.username,
      createdAt: new Date().toISOString(),
      message: null,
    };
    p5.files.push(file);
    return file;
  }
}

/** Decimal fields arrive as numbers from the generated Zod schema; the wire shape is a `0.00` string. */
function leaveTypeFromRequest(v: Record<string, unknown>): Omit<LeaveType, 'leaveTypeId' | 'pendingRequests' | 'createdBy' | 'createdDate' | 'modifiedBy' | 'modifiedDate'> {
  const dec = (x: unknown) => (typeof x === 'number' ? x.toFixed(2) : typeof x === 'string' && x !== '' ? x : null);
  return {
    leaveTypeCode: String(v.leaveTypeCode),
    leaveTypeName: String(v.leaveTypeName),
    paidFlag: (v.paidFlag as boolean | undefined) ?? true,
    accrualFlag: (v.accrualFlag as boolean | undefined) ?? true,
    accrualRate: dec(v.accrualRate),
    accrualFrequency: (v.accrualFrequency as LeaveTypeRequest['accrualFrequency']) ?? null,
    maxBalance: dec(v.maxBalance),
    carryoverMax: dec(v.carryoverMax),
    carryoverExpiry: (v.carryoverExpiry as number | undefined) ?? null,
    minTenureDays: (v.minTenureDays as number | undefined) ?? 0,
    requiresApproval: (v.requiresApproval as boolean | undefined) ?? true,
    requiresDocument: (v.requiresDocument as boolean | undefined) ?? false,
    activeFlag: (v.activeFlag as boolean | undefined) ?? true,
  };
}

function leaveTypeRule(lt: ReturnType<typeof leaveTypeFromRequest>) {
  if (lt.accrualFlag && (!lt.accrualRate || !lt.accrualFrequency)) return valueRule(lt.accrualRate ? 'accrualFrequency' : 'accrualRate', 'Accrual rate and frequency are required when accrual is enabled');
  if (lt.maxBalance && lt.carryoverMax && Number(lt.carryoverMax) > Number(lt.maxBalance)) return valueRule('carryoverMax', 'Carryover maximum must not exceed maximum balance');
  return null;
}

function paramValueRule(dataType: SystemParameter['dataType'], value: string) {
  if (dataType === 'NUMBER' && !/^-?\d+(\.\d+)?$/.test(value)) return valueRule('paramValue', 'Value must be a number');
  if (dataType === 'DATE' && !/^\d{4}-\d{2}-\d{2}$/.test(value)) return valueRule('paramValue', 'Value must be a date (YYYY-MM-DD)');
  if (dataType === 'BOOLEAN' && !/^[YN]$/.test(value)) return valueRule('paramValue', 'Value must be Y or N');
  return null;
}
