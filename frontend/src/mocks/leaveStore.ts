import type { BusinessDaysHoliday, HalfDayPeriod, LeaveBalance, LeaveRequest, LeaveRequestStatus, LeaveTypeRef } from '../api/types';
import { getMockEmployee } from './performanceStore';

/**
 * In-memory `leave-service` state for the msw contract mocks (contracts/p2-leave/openapi.yaml).
 * Balance arithmetic follows VAL-05: available = opening + accrued - used + adjustment - pending.
 */

const DAY_MS = 86_400_000;

export const MOCK_HOLIDAYS: BusinessDaysHoliday[] = [
  { holidayName: 'New Year', holidayDate: '2026-01-01', observedDate: '2026-01-01' },
  { holidayName: 'Independence Day', holidayDate: '2026-07-04', observedDate: '2026-07-03' }, // BUG-05: Saturday -> Friday
  { holidayName: 'Christmas Day', holidayDate: '2026-12-25', observedDate: '2026-12-25' },
];

let requests: LeaveRequest[] = [];
let nextRequestId = 1000;

function seedBalance(balanceId: number, empId: number, type: LeaveTypeRef, year: number, opening: number, accrued: number, used: number, pending: number, carryover: number): LeaveBalance & { empId: number } {
  return {
    balanceId,
    empId,
    leaveTypeId: type.leaveTypeId,
    leaveTypeCode: type.leaveTypeCode,
    leaveTypeName: type.leaveTypeName,
    calendarYear: year,
    openingBalance: opening,
    accrued,
    used,
    adjustment: 0,
    pending,
    carryoverFromPrev: carryover,
    available: round(opening + accrued - used + 0 - pending),
  };
}

let ownedBalances: (LeaveBalance & { empId: number })[] = [];

export function resetLeaveState(leaveTypes: LeaveTypeRef[], today = new Date()) {
  const year = today.getUTCFullYear();
  const [pto, sick] = leaveTypes;
  ownedBalances = [
    seedBalance(1, 11, pto, year, 5, 11.25, 3, 2, 5),
    seedBalance(2, 11, sick, year, 0, 7.47, 1, 0, 0),
    seedBalance(3, 21, pto, year, 5, 11.25, 0, 0, 5),
    seedBalance(4, 21, sick, year, 0, 7.47, 0, 0, 0),
    seedBalance(5, 12, pto, year, 0, 11.25, 0, 3, 0),
    seedBalance(6, 13, pto, year, 0, 11.25, 2, 0, 0),
  ];
  const d = (offset: number) => isoDay(utcDay(today) + offset);
  requests = [
    mk(901, 11, pto, d(-30), d(-28), 'APPROVED', 'Family trip', 21, d(-35)),
    mk(902, 11, pto, d(14), d(15), 'PENDING', 'Long weekend', 21, d(-2)),
    mk(903, 11, sick, d(-60), d(-60), 'CANCELLED', 'Cancelled by employee', null, d(-61)),
    mk(904, 12, pto, d(7), d(9), 'PENDING', 'Conference', 21, d(-1)),
    mk(905, 13, pto, d(3), d(4), 'APPROVED', null, 21, d(-10)),
    mk(906, 11, pto, d(-90), d(-90), 'REJECTED', 'Short notice', 21, d(-95)),
  ];
  nextRequestId = 1000;
}

function mk(requestId: number, empId: number, type: LeaveTypeRef, startDate: string, endDate: string, status: LeaveRequestStatus, reason: string | null, approverEmpId: number | null, createdDate: string): LeaveRequest {
  const emp = getMockEmployee(empId)!;
  const approver = approverEmpId ? getMockEmployee(approverEmpId) : undefined;
  return {
    requestId,
    empId,
    empName: emp.name,
    leaveTypeId: type.leaveTypeId,
    leaveTypeCode: type.leaveTypeCode,
    leaveTypeName: type.leaveTypeName,
    startDate,
    endDate,
    totalDays: businessDays(startDate, endDate).businessDays,
    halfDay: false,
    halfDayPeriod: null,
    status,
    reason,
    approverEmpId,
    approverName: approver?.name ?? null,
    approvalDate: status === 'APPROVED' || status === 'REJECTED' ? `${createdDate}T09:00:00Z` : null,
    approvalComments: status === 'REJECTED' ? 'Team coverage' : null,
    createdDate: `${createdDate}T08:00:00Z`,
    modifiedDate: null,
  };
}

export function utcDay(date: Date | string): number {
  const t = typeof date === 'string' ? Date.parse(`${date}T00:00:00Z`) : Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
  return Math.floor(t / DAY_MS);
}

export function isoDay(day: number): string {
  return new Date(day * DAY_MS).toISOString().slice(0, 10);
}

function round(n: number) {
  return Math.round(n * 100) / 100;
}

/** BUG-05 semantics: weekends excluded, holidays excluded on their observed date. */
export function businessDays(start: string, end: string): { businessDays: number; holidays: BusinessDaysHoliday[] } {
  const s = utcDay(start);
  const e = utcDay(end);
  const observed = new Map(MOCK_HOLIDAYS.map((h) => [h.observedDate, h]));
  let count = 0;
  const holidays: BusinessDaysHoliday[] = [];
  for (let d = s; d <= e; d++) {
    const dow = new Date(d * DAY_MS).getUTCDay();
    if (dow === 0 || dow === 6) continue;
    const h = observed.get(isoDay(d));
    if (h) {
      holidays.push(h);
      continue;
    }
    count++;
  }
  return { businessDays: count, holidays };
}

export function listRequestsFor(empId: number, status?: string, year?: number): LeaveRequest[] {
  const statuses = status ? new Set(status.split(',')) : null;
  return requests
    .filter((r) => r.empId === empId)
    .filter((r) => !statuses || statuses.has(r.status))
    .filter((r) => !year || r.startDate.startsWith(String(year)))
    .sort((a, b) => b.createdDate.localeCompare(a.createdDate) || b.requestId - a.requestId)
    .map(clone);
}

export function getRequest(id: number) {
  return requests.find((r) => r.requestId === id);
}

export function overlaps(empId: number, start: string, end: string, halfDayPeriod: HalfDayPeriod | null) {
  return requests.some((r) => {
    if (r.empId !== empId || (r.status !== 'PENDING' && r.status !== 'APPROVED')) return false;
    if (r.startDate > end || r.endDate < start) return false;
    // BUG-06: AM + PM halves on the same date are not an overlap.
    if (halfDayPeriod && r.halfDay && r.halfDayPeriod && r.halfDayPeriod !== halfDayPeriod && r.startDate === start && r.endDate === end) return false;
    return true;
  });
}

export function balanceFor(empId: number, leaveTypeId: number, year: number) {
  return ownedBalances.find((b) => b.empId === empId && b.leaveTypeId === leaveTypeId && b.calendarYear === year);
}

export function listBalancesFor(empId: number, year?: number): LeaveBalance[] {
  return ownedBalances
    .filter((b) => b.empId === empId && (!year || b.calendarYear === year))
    .map((b) => { const { empId: _owner, ...rest } = b; void _owner; return clone(rest); });
}

export function adjustBalance(empId: number, leaveTypeId: number, year: number, delta: { pending?: number; used?: number }) {
  const b = balanceFor(empId, leaveTypeId, year);
  if (!b) return; // QUIRK-02
  b.pending = round(b.pending + (delta.pending ?? 0));
  b.used = round(b.used + (delta.used ?? 0));
  b.available = round(b.openingBalance + b.accrued - b.used + b.adjustment - b.pending);
}

export function insertRequest(input: Omit<LeaveRequest, 'requestId' | 'createdDate' | 'modifiedDate'>): LeaveRequest {
  const r: LeaveRequest = { ...input, requestId: nextRequestId++, createdDate: new Date().toISOString(), modifiedDate: null };
  requests.push(r);
  return r;
}

export function pendingApprovalsFor(approverEmpId: number) {
  return requests
    .filter((r) => r.approverEmpId === approverEmpId && r.status === 'PENDING')
    .sort((a, b) => a.createdDate.localeCompare(b.createdDate) || a.requestId - b.requestId);
}

export function teamCalendarFor(managerEmpId: number, from: string, to: string) {
  return requests
    .filter((r) => getMockEmployee(r.empId)?.managerEmpId === managerEmpId)
    .filter((r) => r.status === 'APPROVED' || r.status === 'TAKEN')
    .filter((r) => r.startDate <= to && r.endDate >= from)
    .sort((a, b) => a.startDate.localeCompare(b.startDate) || a.empName.localeCompare(b.empName));
}

export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
