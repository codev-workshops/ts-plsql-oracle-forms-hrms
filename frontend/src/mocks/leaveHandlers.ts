import { HttpResponse, http } from 'msw';
import type {
  ApiError,
  Authority,
  HalfDayPeriod,
  LeaveApproveRequest,
  LeaveCancelRequest,
  LeaveRejectRequest,
  LeaveRequestCreateRequest,
  LeaveRequestStatus,
  LeaveTypeRef,
  PageOfLeaveRequest,
} from '../api/types';
import { getDto, isoToday } from '../validation/schema';
import {
  availableFor,
  clone,
  createLeaveRequest,
  currentYear,
  getLeaveEmployee,
  getLeaveRequest,
  listBalances,
  listLeaveRequests,
  mockBusinessDays,
  overlaps,
  pendingApprovals,
  teamCalendar,
  transition,
} from './leaveStore';

/**
 * msw implementation of contracts/p2-leave/openapi.yaml (Phase 2 routes only; the
 * deferred P5 admin routes are not mounted). Codes/messages: contracts/p2-leave/error-codes.md.
 */

interface SessionUser {
  userId: string;
  empId: number;
  roles: Authority[];
}

type Authenticate = (request: Request) => SessionUser | null;

const TRACE = '7a2b4c6d8e0f1a23';
const STATUSES: LeaveRequestStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'TAKEN'];
const PAST_LIMIT_DAYS = Number(getDto('LeaveRequestCreateRequest').fields.startDate.rules?.find((r) => r.id === 'leave.pastLimit')?.value ?? 5);
const MESSAGES = {
  employeeNotFound: 'Employee not found or not active',
  insufficient: (available: number, requested: number) => `Insufficient leave balance. Available: ${available}, Requested: ${requested}`,
  overlap: 'Leave request overlaps with existing request',
  invalidType: (id: number) => `Invalid leave type: ${id}`,
  tenure: (days: number, name: string) => `Minimum tenure of ${days} days not met for leave type: ${name}`,
  transition: (op: string, status: string) => `Cannot ${op} request in status: ${status}`,
  dateOrder: 'Start date must be before or equal to end date',
  pastLimit: `Cannot submit leave requests more than ${PAST_LIMIT_DAYS} days in the past`,
  noBusinessDay: 'Leave request must include at least one business day',
  notFound: 'Leave request not found',
} as const;

function error(status: number, body: Omit<ApiError, 'traceId'>) {
  return HttpResponse.json<ApiError>({ ...body, traceId: TRACE }, { status });
}

const unauthorized = () => error(401, { code: 'TOKEN_INVALID', message: 'Session has expired' });
const forbidden = () => error(403, { code: 'FORBIDDEN', message: 'You do not have permission to perform this action' });
const validation = (field: string, message = 'Request validation failed') => error(400, { code: 'VALIDATION_FAILED', message, field });
const notFound = () => error(404, { code: 'LEAVE_REQUEST_NOT_FOUND', message: MESSAGES.notFound });
const employeeNotFound = (field?: string) => error(404, { code: '-20001', message: MESSAGES.employeeNotFound, ...(field ? { field } : {}) });
const badTransition = (op: string, status: string) => error(422, { code: '-20204', message: MESSAGES.transition(op, status) });

async function body<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    return {} as T;
  }
}

function has(user: SessionUser, authority: Authority) {
  return user.roles.includes(authority);
}

function validDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`));
}

function daysBetween(from: string, to: string) {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

function parseStatus(url: URL): LeaveRequestStatus[] | null | 'invalid' {
  const raw = url.searchParams.get('status');
  if (raw === null || raw === '') return null;
  const parts = raw.split(',');
  return parts.every((p): p is LeaveRequestStatus => STATUSES.includes(p as LeaveRequestStatus)) ? parts : 'invalid';
}

function parseYear(url: URL): number | undefined | 'invalid' {
  const raw = url.searchParams.get('year');
  if (raw === null || raw === '') return undefined;
  const year = Number(raw);
  return Number.isInteger(year) && year >= 2000 && year <= 2099 ? year : 'invalid';
}

function idOf(params: Record<string, string | readonly string[] | undefined>) {
  const raw = params.id;
  return Number(Array.isArray(raw) ? raw[0] : raw);
}

function rangeError(url: URL, startName: string, endName: string) {
  const start = url.searchParams.get(startName);
  const end = url.searchParams.get(endName);
  if (!validDate(start)) return { response: validation(startName, `${startName} is required`) };
  if (!validDate(end)) return { response: validation(endName, `${endName} is required`) };
  if (start > end) return { response: error(400, { code: '-20210', message: MESSAGES.dateOrder, field: endName }) };
  if (daysBetween(start, end) > 366) return { response: validation(endName, 'Range must not exceed 366 days') };
  return { start, end };
}

export function createLeaveHandlers(authenticate: Authenticate, leaveTypes: () => LeaveTypeRef[]) {
  const leaveTypeById = (id: number) => leaveTypes().find((t) => t.leaveTypeId === id && t.active);

  return [
    http.get('/api/leave/requests/mine', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!getLeaveEmployee(user.empId)) return employeeNotFound();
      const url = new URL(request.url);
      const status = parseStatus(url);
      if (status === 'invalid') return validation('status');
      const year = parseYear(url);
      if (year === 'invalid') return validation('year');
      return HttpResponse.json(clone(listLeaveRequests({ empId: user.empId, status: status ?? undefined, year })));
    }),

    http.get('/api/leave/requests', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!has(user, 'LEAVE:VIEW_ALL')) return forbidden();
      const url = new URL(request.url);
      const empId = Number(url.searchParams.get('empId'));
      if (!Number.isInteger(empId) || empId < 1) return validation('empId', 'empId is required');
      if (!getLeaveEmployee(empId)) return employeeNotFound('empId');
      const status = parseStatus(url);
      if (status === 'invalid') return validation('status');
      const year = parseYear(url);
      if (year === 'invalid') return validation('year');
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Number(url.searchParams.get('size') ?? 20);
      const rows = listLeaveRequests({ empId, status: status ?? undefined, year });
      const content = rows.slice(page * size, page * size + size);
      const body: PageOfLeaveRequest = { content: clone(content), page, size, totalElements: rows.length, totalPages: Math.max(1, Math.ceil(rows.length / size)) };
      return HttpResponse.json(body);
    }),

    http.post('/api/leave/requests', async ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!has(user, 'LEAVE:CREATE')) return forbidden();
      const dto = getDto('LeaveRequestCreateRequest');
      const input = await body<Partial<LeaveRequestCreateRequest>>(request);

      // Bean Validation (shape from validation-schema.json)
      if (typeof input.leaveTypeId !== 'number' || !Number.isInteger(input.leaveTypeId) || input.leaveTypeId < 1) return validation('leaveTypeId', dto.fields.leaveTypeId.messages.required);
      if (!validDate(input.startDate)) return validation('startDate', dto.fields.startDate.messages.required);
      if (!validDate(input.endDate)) return validation('endDate', dto.fields.endDate.messages.required);
      if (input.endDate < input.startDate) return error(400, { code: '-20210', message: MESSAGES.dateOrder, field: 'endDate' });
      const halfDay = input.halfDay === true;
      const halfDayPeriod: HalfDayPeriod | null = input.halfDayPeriod ?? null;
      if (halfDay && input.startDate !== input.endDate) return validation('endDate', dto.fields.halfDay.messages.format);
      if (halfDay && !(dto.fields.halfDayPeriod.values ?? []).includes(String(halfDayPeriod))) return validation('halfDayPeriod', dto.fields.halfDayPeriod.messages.required);
      if (halfDayPeriod !== null && !(dto.fields.halfDayPeriod.values ?? []).includes(halfDayPeriod)) return validation('halfDayPeriod');
      const reason = typeof input.reason === 'string' ? input.reason.trim() || null : null;
      if (reason !== null && reason.length > Number(dto.fields.reason.maxLength ?? 4000)) return validation('reason', dto.fields.reason.messages.maxLength);

      // Service rules, contract order
      const emp = getLeaveEmployee(user.empId);
      if (!emp || !emp.active) return employeeNotFound();
      const type = leaveTypeById(input.leaveTypeId);
      if (!type) return error(422, { code: '-20203', message: MESSAGES.invalidType(input.leaveTypeId), field: 'leaveTypeId' });
      const today = isoToday();
      if (daysBetween(emp.hireDate, today) < type.minTenureDays) return error(422, { code: '-20203', message: MESSAGES.tenure(type.minTenureDays, type.leaveTypeName), field: 'leaveTypeId' });
      if (daysBetween(input.startDate, today) > PAST_LIMIT_DAYS) return error(400, { code: '-20211', message: MESSAGES.pastLimit, field: 'startDate' });
      const businessDays = mockBusinessDays(input.startDate, input.endDate).businessDays;
      const totalDays = halfDay ? 0.5 : businessDays;
      if (totalDays <= 0 || (halfDay && businessDays === 0)) return error(422, { code: '-20212', message: MESSAGES.noBusinessDay, field: 'startDate' });
      if (overlaps(user.empId, input.startDate, input.endDate, halfDay, halfDay ? halfDayPeriod : null)) return error(409, { code: '-20202', message: MESSAGES.overlap, field: 'startDate' });
      if (type.accrual) {
        const available = availableFor(user.empId, type.leaveTypeId, currentYear());
        if (available < totalDays) return error(422, { code: '-20201', message: MESSAGES.insufficient(available, totalDays), field: 'leaveTypeId' });
      }

      const created = createLeaveRequest(leaveTypes(), {
        empId: user.empId,
        leaveTypeId: type.leaveTypeId,
        startDate: input.startDate,
        endDate: input.endDate,
        halfDay,
        halfDayPeriod: halfDay ? halfDayPeriod : null,
        reason,
        totalDays,
      });
      return HttpResponse.json(clone(created), { status: 201, headers: { Location: `/api/leave/requests/${created.requestId}` } });
    }),

    http.get('/api/leave/requests/:id', ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const row = getLeaveRequest(idOf(params));
      if (!row) return notFound();
      if (row.empId !== user.empId && row.approverEmpId !== user.empId && !has(user, 'LEAVE:VIEW_ALL')) return forbidden();
      return HttpResponse.json(clone(row));
    }),

    http.post('/api/leave/requests/:id/cancel', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const row = getLeaveRequest(idOf(params));
      if (!row) return notFound();
      if (row.empId !== user.empId) return forbidden();
      if (row.status !== 'PENDING' && row.status !== 'APPROVED') return badTransition('cancel', row.status);
      const input = await body<LeaveCancelRequest>(request);
      const reason = typeof input.reason === 'string' && input.reason.trim() ? input.reason.trim() : 'Cancelled by employee';
      if (reason.length > 4000) return validation('reason');
      return HttpResponse.json(clone(transition(leaveTypes(), row, 'CANCELLED', { empId: user.empId, asApprover: false }, reason)));
    }),

    http.post('/api/leave/requests/:id/approve', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const row = getLeaveRequest(idOf(params));
      if (!row) return notFound();
      if (row.empId === user.empId) return forbidden();
      if (row.approverEmpId !== user.empId && !has(user, 'LEAVE:APPROVE')) return forbidden();
      if (row.status !== 'PENDING') return badTransition('approve', row.status);
      const input = await body<LeaveApproveRequest>(request);
      const comments = typeof input.comments === 'string' && input.comments.trim() ? input.comments.trim() : null;
      if (comments !== null && comments.length > 4000) return validation('comments');
      return HttpResponse.json(clone(transition(leaveTypes(), row, 'APPROVED', { empId: user.empId, asApprover: true }, comments)));
    }),

    http.post('/api/leave/requests/:id/reject', async ({ request, params }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const row = getLeaveRequest(idOf(params));
      if (!row) return notFound();
      if (row.empId === user.empId) return forbidden();
      if (row.approverEmpId !== user.empId && !has(user, 'LEAVE:APPROVE')) return forbidden();
      if (row.status !== 'PENDING') return badTransition('reject', row.status);
      const input = await body<Partial<LeaveRejectRequest>>(request);
      const dto = getDto('LeaveRejectRequest');
      const comments = typeof input.comments === 'string' ? input.comments.trim() : '';
      if (!comments) return validation('comments', dto.fields.comments.messages.required);
      if (comments.length > Number(dto.fields.comments.maxLength ?? 4000)) return validation('comments', dto.fields.comments.messages.maxLength);
      return HttpResponse.json(clone(transition(leaveTypes(), row, 'REJECTED', { empId: user.empId, asApprover: true }, comments)));
    }),

    http.get('/api/leave/balances/mine', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!getLeaveEmployee(user.empId)) return employeeNotFound();
      const year = parseYear(new URL(request.url));
      if (year === 'invalid') return validation('year');
      return HttpResponse.json(clone(listBalances(user.empId, year ?? currentYear())));
    }),

    http.get('/api/leave/business-days', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const range = rangeError(new URL(request.url), 'start', 'end');
      if ('response' in range) return range.response;
      return HttpResponse.json(mockBusinessDays(range.start, range.end));
    }),

    http.get('/api/leave/approvals/pending', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      if (!getLeaveEmployee(user.empId)) return employeeNotFound();
      return HttpResponse.json(clone(pendingApprovals(user.empId)));
    }),

    http.get('/api/leave/team-calendar', ({ request }) => {
      const user = authenticate(request);
      if (!user) return unauthorized();
      const range = rangeError(new URL(request.url), 'from', 'to');
      if ('response' in range) return range.response;
      return HttpResponse.json(clone(teamCalendar(user.empId, range.start, range.end)));
    }),
  ];
}
