import { HttpResponse, http } from 'msw';
import type {
  ApiError,
  Authority,
  LeaveApproveRequest,
  LeaveCancelRequest,
  LeaveRejectRequest,
  LeaveRequest,
  LeaveRequestCreateRequest,
  LeaveTypeRef,
  PendingLeaveApproval,
  TeamCalendarEntry,
} from '../api/types';
import { getDto } from '../validation/schema';
import { getMockEmployee } from './performanceStore';
import {
  adjustBalance,
  balanceFor,
  businessDays,
  clone,
  getRequest,
  insertRequest,
  listBalancesFor,
  listRequestsFor,
  overlaps,
  pendingApprovalsFor,
  teamCalendarFor,
  utcDay,
} from './leaveStore';

interface SessionUser {
  userId: string;
  empId: number;
  roles: Authority[];
}

type Authenticate = (request: Request) => SessionUser | null;

const TRACE = '7a2b9c4d1e8f4a03';
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function error(status: number, body: Omit<ApiError, 'traceId'>) {
  return HttpResponse.json<ApiError>({ ...body, traceId: TRACE }, { status });
}

const unauthorized = () => error(401, { code: 'TOKEN_INVALID', message: 'Session has expired' });
const forbidden = () => error(403, { code: 'FORBIDDEN', message: 'You do not have permission to perform this action' });
const validation = (field: string, message = 'Request validation failed') => error(400, { code: 'VALIDATION_FAILED', message, field });
const notFound = () => error(404, { code: 'LEAVE_REQUEST_NOT_FOUND', message: 'Leave request not found' });
const employeeNotFound = () => error(404, { code: '-20001', message: 'Employee not found or not active' });

async function body<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    return {} as T;
  }
}

type PathValue = string | readonly string[] | undefined;

function requestId(params: Record<string, PathValue>) {
  const v = params.id;
  return Number(Array.isArray(v) ? v[0] : v);
}

function dateParam(url: URL, name: string) {
  const v = url.searchParams.get(name);
  return v && ISO_DATE.test(v) ? v : null;
}

function canAct(user: SessionUser, r: LeaveRequest) {
  return r.approverEmpId === user.empId || user.roles.includes('LEAVE:APPROVE');
}

function toPending(r: LeaveRequest): PendingLeaveApproval {
  const emp = getMockEmployee(r.empId);
  return {
    requestId: r.requestId,
    empId: r.empId,
    empNumber: `EMP-${String(r.empId).padStart(6, '0')}`,
    empName: emp?.name ?? r.empName,
    leaveTypeName: r.leaveTypeName,
    startDate: r.startDate,
    endDate: r.endDate,
    totalDays: r.totalDays,
    halfDay: r.halfDay,
    halfDayPeriod: r.halfDayPeriod,
    reason: r.reason,
    createdDate: r.createdDate,
  };
}

function toCalendar(r: LeaveRequest): TeamCalendarEntry {
  return {
    requestId: r.requestId,
    empId: r.empId,
    empName: r.empName,
    leaveTypeName: r.leaveTypeName,
    startDate: r.startDate,
    endDate: r.endDate,
    totalDays: r.totalDays,
    halfDay: r.halfDay,
    halfDayPeriod: r.halfDayPeriod,
    status: r.status === 'TAKEN' ? 'TAKEN' : 'APPROVED',
  };
}

export function createLeaveHandlers(authenticate: Authenticate, leaveTypes: () => LeaveTypeRef[]) {
  return [
    http.get('/api/leave/requests/mine', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const url = new URL(request.url);
      const year = url.searchParams.get('year');
      return HttpResponse.json(listRequestsFor(user.empId, url.searchParams.get('status') ?? undefined, year ? Number(year) : undefined));
    }),

    http.get('/api/leave/requests', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('LEAVE:VIEW_ALL')) return forbidden();
      const url = new URL(request.url);
      const empId = Number(url.searchParams.get('empId'));
      if (!empId) return validation('empId');
      if (!getMockEmployee(empId)) return employeeNotFound();
      const rows = listRequestsFor(empId, url.searchParams.get('status') ?? undefined);
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Number(url.searchParams.get('size') ?? 20);
      return HttpResponse.json({ content: rows.slice(page * size, page * size + size), page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) });
    }),

    http.post('/api/leave/requests', async ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!user.roles.includes('LEAVE:CREATE')) return forbidden();
      const emp = getMockEmployee(user.empId);
      if (!emp?.active) return employeeNotFound();
      const b = await body<LeaveRequestCreateRequest>(request);
      const spec = getDto('LeaveRequestCreateRequest').fields;
      if (!Number.isInteger(b.leaveTypeId) || b.leaveTypeId < 1) return validation('leaveTypeId', spec.leaveTypeId.messages.required);
      if (!b.startDate || !ISO_DATE.test(b.startDate)) return validation('startDate', spec.startDate.messages.required);
      if (!b.endDate || !ISO_DATE.test(b.endDate)) return validation('endDate', spec.endDate.messages.required);
      const halfDay = b.halfDay === true;
      if (halfDay && (b.halfDayPeriod !== 'AM' && b.halfDayPeriod !== 'PM')) return validation('halfDayPeriod', spec.halfDayPeriod.messages.required);
      if (halfDay && b.startDate !== b.endDate) return validation('endDate', spec.halfDay.messages.format);
      const reason = typeof b.reason === 'string' && b.reason.trim() ? b.reason.trim() : null;
      if (reason && reason.length > (spec.reason.maxLength ?? Infinity)) return validation('reason');

      // Evaluation order per error-codes.md §4.
      const type = leaveTypes().find((t) => t.leaveTypeId === b.leaveTypeId);
      if (!type) return error(422, { code: '-20203', message: 'Invalid leave type', field: 'leaveTypeId' });
      if (b.startDate > b.endDate) return error(422, { code: '-20210', message: spec.endDate.rules![0].message, field: 'endDate' });
      const todayDay = utcDay(new Date());
      const limit = Number(spec.startDate.rules![0].value);
      if (utcDay(b.startDate) < todayDay - limit) return error(422, { code: '-20211', message: spec.startDate.rules![0].message, field: 'startDate' });
      const totalDays = halfDay ? 0.5 : businessDays(b.startDate, b.endDate).businessDays;
      if (totalDays === 0) return error(422, { code: '-20212', message: 'Leave request must include at least one business day', field: 'startDate' });
      if (overlaps(user.empId, b.startDate, b.endDate, halfDay ? b.halfDayPeriod! : null)) {
        return error(422, { code: '-20202', message: 'Leave request overlaps with an existing request', field: 'startDate' });
      }
      const currentYear = new Date().getUTCFullYear(); // QUIRK-01
      const balance = balanceFor(user.empId, type.leaveTypeId, currentYear);
      const available = balance?.available ?? 0;
      if (type.accrual && available < totalDays) {
        return error(422, { code: '-20201', message: `Insufficient leave balance. Available: ${available}, Requested: ${totalDays}`, field: 'leaveTypeId' });
      }

      const status = type.requiresApproval ? 'PENDING' : 'APPROVED';
      const approverEmpId = emp.managerEmpId;
      const approver = approverEmpId ? getMockEmployee(approverEmpId) : undefined;
      const created = insertRequest({
        empId: user.empId,
        empName: emp.name,
        leaveTypeId: type.leaveTypeId,
        leaveTypeCode: type.leaveTypeCode,
        leaveTypeName: type.leaveTypeName,
        startDate: b.startDate,
        endDate: b.endDate,
        totalDays,
        halfDay,
        halfDayPeriod: halfDay ? b.halfDayPeriod! : null,
        status,
        reason,
        approverEmpId,
        approverName: approver?.name ?? null,
        approvalDate: status === 'APPROVED' ? new Date().toISOString() : null,
        approvalComments: null,
      });
      const year = Number(b.startDate.slice(0, 4));
      adjustBalance(user.empId, type.leaveTypeId, year, status === 'PENDING' ? { pending: totalDays } : { used: totalDays });
      return HttpResponse.json(clone(created), { status: 201 });
    }),

    http.get('/api/leave/requests/:id', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const r = getRequest(requestId(params));
      if (!r) return notFound();
      if (r.empId !== user.empId && r.approverEmpId !== user.empId && !user.roles.includes('LEAVE:VIEW_ALL')) return forbidden();
      return HttpResponse.json(clone(r));
    }),

    http.post('/api/leave/requests/:id/cancel', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const r = getRequest(requestId(params));
      if (!r) return notFound();
      if (r.empId !== user.empId) return forbidden();
      if (r.status !== 'PENDING' && r.status !== 'APPROVED') {
        return error(422, { code: '-20204', message: `Cannot cancel request in status: ${r.status}` });
      }
      const b = await body<LeaveCancelRequest>(request);
      const year = Number(r.startDate.slice(0, 4));
      adjustBalance(r.empId, r.leaveTypeId, year, r.status === 'PENDING' ? { pending: -r.totalDays } : { used: -r.totalDays });
      r.status = 'CANCELLED';
      r.reason = typeof b.reason === 'string' && b.reason.trim() ? b.reason.trim() : 'Cancelled by employee';
      r.modifiedDate = new Date().toISOString();
      return HttpResponse.json(clone(r));
    }),

    http.post('/api/leave/requests/:id/approve', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const r = getRequest(requestId(params));
      if (!r) return notFound();
      if (r.empId === user.empId || !canAct(user, r)) return forbidden();
      if (r.status !== 'PENDING') return error(422, { code: '-20204', message: `Cannot approve request in status: ${r.status}` });
      const b = await body<LeaveApproveRequest>(request);
      adjustBalance(r.empId, r.leaveTypeId, Number(r.startDate.slice(0, 4)), { pending: -r.totalDays, used: r.totalDays });
      r.status = 'APPROVED';
      r.approverEmpId = user.empId;
      r.approverName = getMockEmployee(user.empId)?.name ?? null;
      r.approvalDate = new Date().toISOString();
      r.approvalComments = typeof b.comments === 'string' && b.comments.trim() ? b.comments.trim() : null;
      r.modifiedDate = r.approvalDate;
      return HttpResponse.json(clone(r));
    }),

    http.post('/api/leave/requests/:id/reject', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const r = getRequest(requestId(params));
      if (!r) return notFound();
      if (r.empId === user.empId || !canAct(user, r)) return forbidden();
      const b = await body<LeaveRejectRequest>(request);
      const comments = typeof b.comments === 'string' ? b.comments.trim() : '';
      if (!comments) return validation('comments', getDto('LeaveRejectRequest').fields.comments.messages.required);
      if (r.status !== 'PENDING') return error(422, { code: '-20204', message: `Cannot reject request in status: ${r.status}` });
      adjustBalance(r.empId, r.leaveTypeId, Number(r.startDate.slice(0, 4)), { pending: -r.totalDays });
      r.status = 'REJECTED';
      r.approverEmpId = user.empId;
      r.approverName = getMockEmployee(user.empId)?.name ?? null;
      r.approvalDate = new Date().toISOString();
      r.approvalComments = comments;
      r.modifiedDate = r.approvalDate;
      return HttpResponse.json(clone(r));
    }),

    http.get('/api/leave/balances/mine', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const year = new URL(request.url).searchParams.get('year');
      return HttpResponse.json(listBalancesFor(user.empId, year ? Number(year) : undefined));
    }),

    http.get('/api/leave/business-days', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const url = new URL(request.url);
      const start = dateParam(url, 'start');
      const end = dateParam(url, 'end');
      if (!start) return validation('start');
      if (!end) return validation('end');
      if (start > end) return error(400, { code: '-20210', message: 'Start date must be before or equal to end date', field: 'end' });
      return HttpResponse.json({ start, end, ...businessDays(start, end) });
    }),

    http.get('/api/leave/approvals/pending', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      return HttpResponse.json(pendingApprovalsFor(user.empId).map(toPending));
    }),

    http.get('/api/leave/team-calendar', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const url = new URL(request.url);
      const from = dateParam(url, 'from');
      const to = dateParam(url, 'to');
      if (!from) return validation('from');
      if (!to) return validation('to');
      if (from > to) return error(400, { code: '-20210', message: 'Start date must be before or equal to end date', field: 'to' });
      if (utcDay(to) - utcDay(from) > 366) return validation('to', 'Range must not exceed 366 days');
      return HttpResponse.json(teamCalendarFor(user.empId, from, to).map(toCalendar));
    }),
  ];
}
