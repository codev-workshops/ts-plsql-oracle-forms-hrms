import { HttpResponse, http } from 'msw';
import type { ApiError, Authority, DetailStatus, PayPeriodSort, PayrollRunApproval, PeriodStatus, RunStatus } from '../api/types';
import { getDto, zodFor, type DtoName } from '../validation/schema';
import {
  approveRun,
  buildPayslip,
  buildRegister,
  buildShadowDiff,
  clone,
  closePeriod,
  detailsForRun,
  getPeriod,
  getRun,
  insertRun,
  listPeriods,
  pollStatus,
  reverseRun,
  runsForPeriod,
  startCalculation,
} from './payrollStore';

/**
 * msw implementation of contracts/p4-payroll/openapi.yaml + error-codes.md. The mock stands in
 * for `payroll=NEW` / `payroll.engine=JAVA`; `calculate` returns 202 and the simulated batch job
 * completes on the second `/status` poll (see payrollStore.startCalculation).
 */

interface SessionUser {
  userId: string;
  empId: number;
  username: string;
  roles: Authority[];
}

type Authenticate = (request: Request) => SessionUser | null;

const TRACE = '4f7a1c9e2b6d4a10';
const PERIOD_STATUSES: PeriodStatus[] = ['OPEN', 'PROCESSING', 'CLOSED', 'REVERSED'];
const RUN_STATUSES: RunStatus[] = ['PENDING', 'CALCULATING', 'CALCULATED', 'APPROVED', 'PAID', 'REVERSED', 'ERROR'];
const DETAIL_STATUSES: DetailStatus[] = ['CALCULATED', 'ERROR', 'REVERSED'];

function error(status: number, body: Omit<ApiError, 'traceId'>) {
  return HttpResponse.json<ApiError>({ ...body, traceId: TRACE }, { status });
}

const unauthorized = () => error(401, { code: 'TOKEN_INVALID', message: 'Session has expired' });
const forbidden = () => error(403, { code: 'FORBIDDEN', message: 'You do not have permission to perform this action' });
const validation = (field: string, message = 'Request validation failed') => error(400, { code: 'VALIDATION_FAILED', message, field });
const periodNotFound = () => error(404, { code: 'PERIOD_NOT_FOUND', message: 'Pay period not found' });
const runNotFound = () => error(404, { code: 'RUN_NOT_FOUND', message: 'Payroll run not found' });

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

function validateDto(dto: DtoName, payload: Record<string, unknown>) {
  const spec = getDto(dto);
  for (const name of Object.keys(payload)) if (!(name in spec.fields)) return validation(name, `Unknown property ${name}`);
  const parsed = zodFor(dto).safeParse(payload);
  if (parsed.success) return null;
  const issue = parsed.error.issues[0];
  const field = String(issue.path[0] ?? '');
  return error(400, { code: 'VALIDATION_FAILED', message: issue.message, field, details: [{ field, code: 'Invalid', message: issue.message }] });
}

function page<T>(items: T[], url: URL, maxSize: number): { content: T[]; page: number; size: number; totalElements: number; totalPages: number } {
  const p = Math.max(0, Number(url.searchParams.get('page') ?? 0));
  const size = Math.min(maxSize, Math.max(1, Number(url.searchParams.get('size') ?? 20)));
  return { content: items.slice(p * size, (p + 1) * size), page: p, size, totalElements: items.length, totalPages: Math.max(1, Math.ceil(items.length / size)) };
}

function csvFilename(runId: number, periodName: string) {
  return `PAY_REGISTER_${runId}_${periodName.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_|_$/g, '')}.csv`;
}

export function createPayrollHandlers(authenticate: Authenticate) {
  const canView = (u: SessionUser) => u.roles.includes('PAYROLL:VIEW');
  const canApprove = (u: SessionUser) => u.roles.includes('PAYROLL:APPROVE');

  return [
    http.get('/api/payroll/periods', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canView(user)) return forbidden();
      const url = new URL(request.url);
      const status = url.searchParams.get('status');
      if (status !== null && !PERIOD_STATUSES.includes(status as PeriodStatus)) return validation('status');
      const sort = (url.searchParams.get('sort') ?? 'periodStartDate,desc') as PayPeriodSort;
      if (!/^(periodStartDate|periodEndDate|payDate|periodName),(asc|desc)$/.test(sort)) return validation('sort');
      const [key, dir] = sort.split(',') as ['periodStartDate' | 'periodEndDate' | 'payDate' | 'periodName', 'asc' | 'desc'];
      const rows = listPeriods()
        .filter((p) => !status || p.status === status)
        .sort((a, b) => (a[key] < b[key] ? -1 : a[key] > b[key] ? 1 : 0) * (dir === 'asc' ? 1 : -1));
      return HttpResponse.json(clone(page(rows, url, 100)));
    }),

    http.post('/api/payroll/periods/:periodId/close', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canApprove(user)) return forbidden();
      const p = getPeriod(num(params.periodId));
      if (!p) return periodNotFound();
      if (p.status === 'CLOSED') return error(422, { code: '-20102', message: 'Pay period is already closed' });
      closePeriod(p, user.username);
      return HttpResponse.json(clone(p));
    }),

    http.get('/api/payroll/periods/:periodId/runs', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canView(user)) return forbidden();
      const p = getPeriod(num(params.periodId));
      if (!p) return periodNotFound();
      const status = new URL(request.url).searchParams.get('status');
      if (status !== null && !RUN_STATUSES.includes(status as RunStatus)) return validation('status');
      return HttpResponse.json(clone(runsForPeriod(p.periodId).filter((r) => !status || r.status === status)));
    }),

    http.post('/api/payroll/periods/:periodId/runs', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canApprove(user)) return forbidden();
      const p = getPeriod(num(params.periodId));
      if (!p) return periodNotFound();
      const payload = await body<Record<string, unknown>>(request);
      const invalid = validateDto('PayrollRunCreateRequest', payload);
      if (invalid) return invalid;
      if (p.status === 'CLOSED') return error(422, { code: '-20102', message: 'Pay period is already closed' });
      const run = insertRun(p.periodId, payload.runType as 'REGULAR', user.username);
      return HttpResponse.json(clone(run), { status: 201 });
    }),

    http.post('/api/payroll/runs/:runId/calculate', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canApprove(user)) return forbidden();
      const run = getRun(num(params.runId));
      if (!run) return runNotFound();
      if (run.status === 'CALCULATING') return error(409, { code: 'RUN_ALREADY_CALCULATING', message: 'Payroll run is already being calculated' });
      if (!['PENDING', 'CALCULATED', 'ERROR'].includes(run.status)) return error(422, { code: 'RUN_NOT_CALCULABLE', message: `Cannot calculate run in status ${run.status}` });
      return HttpResponse.json(startCalculation(run), { status: 202 });
    }),

    http.get('/api/payroll/runs/:runId/status', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canView(user)) return forbidden();
      const run = getRun(num(params.runId));
      if (!run) return runNotFound();
      return HttpResponse.json(pollStatus(run));
    }),

    http.post('/api/payroll/runs/:runId/approve', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canApprove(user)) return forbidden();
      const run = getRun(num(params.runId));
      if (!run) return runNotFound();
      if (run.status !== 'CALCULATED') return error(422, { code: '-20103', message: `Cannot approve run in current status: ${run.status}` });
      const warnings = detailsForRun(run.runId, 'ERROR').map((d) => ({ empId: d.empId, empNumber: d.empNumber, errorCode: d.errorCode!, errorMessage: d.errorMessage! }));
      approveRun(run, user.username);
      const approval: PayrollRunApproval = { run, warnings };
      return HttpResponse.json(clone(approval));
    }),

    http.post('/api/payroll/runs/:runId/reverse', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canApprove(user)) return forbidden();
      const run = getRun(num(params.runId));
      if (!run) return runNotFound();
      const payload = await body<Record<string, unknown>>(request);
      const invalid = validateDto('PayrollRunReverseRequest', payload);
      if (invalid) return invalid;
      if (!['CALCULATED', 'APPROVED'].includes(run.status)) return error(422, { code: 'RUN_NOT_REVERSIBLE', message: `Cannot reverse run in status ${run.status}` });
      reverseRun(run);
      return HttpResponse.json(clone(run));
    }),

    http.get('/api/payroll/runs/:runId/details', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canView(user)) return forbidden();
      const run = getRun(num(params.runId));
      if (!run) return runNotFound();
      const url = new URL(request.url);
      const status = url.searchParams.get('status');
      if (status !== null && !DETAIL_STATUSES.includes(status as DetailStatus)) return validation('status');
      const empId = url.searchParams.get('empId');
      if (empId !== null && !(Number(empId) >= 1)) return validation('empId');
      const rows = detailsForRun(run.runId, (status as DetailStatus | null) ?? undefined).filter((d) => empId === null || d.empId === Number(empId));
      return HttpResponse.json(clone(page(rows, url, 500)));
    }),

    http.get('/api/payroll/runs/:runId/payslips/:empId', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const empId = num(params.empId);
      // Row-level scope: PAYROLL:VIEW or the JWT subject *is* the payslip employee.
      if (!canView(user) && user.empId !== empId) return forbidden();
      const run = getRun(num(params.runId));
      if (!run) return runNotFound();
      const slip = buildPayslip(run, empId);
      if (!slip) return error(404, { code: 'PAYSLIP_NOT_FOUND', message: 'No payslip for this employee in this run' });
      if ('errorCode' in slip) return error(422, { code: slip.errorCode, message: slip.errorMessage });
      return HttpResponse.json(slip);
    }),

    http.get('/api/payroll/runs/:runId/register.csv', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!canView(user)) return forbidden();
      const run = getRun(num(params.runId));
      if (!run) return runNotFound();
      const includeBank = new URL(request.url).searchParams.get('includeBank') === 'true';
      if (includeBank && !canApprove(user)) return forbidden();
      const p = getPeriod(run.periodId)!;
      return new HttpResponse(buildRegister(run, includeBank), {
        status: 200,
        headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${csvFilename(run.runId, p.periodName)}"` },
      });
    }),

    http.get('/api/payroll/shadow/runs/:runId/diff', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('ADMIN:VIEW')) return forbidden();
      const run = getRun(num(params.runId));
      if (!run) return runNotFound();
      const report = buildShadowDiff(run);
      if (!report) return error(404, { code: 'SHADOW_REPORT_NOT_FOUND', message: 'No shadow diff for this run' });
      return HttpResponse.json(report);
    }),
  ];
}
