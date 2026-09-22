import type {
  BusinessDays,
  BusinessDaysHoliday,
  HalfDayPeriod,
  LeaveBalance,
  LeaveRequest,
  LeaveRequestStatus,
  LeaveTypeRef,
  PendingLeaveApproval,
  TeamCalendarEntry,
} from '../api/types';
import { isoToday } from '../validation/schema';

/**
 * In-memory model of contracts/p2-leave/openapi.yaml for msw. Mirrors the
 * PKG_LEAVE semantics the contract froze (balance formula, overlap incl. BUG-06,
 * observed holidays BUG-05) so the pages can be exercised without a backend.
 * Fixture dates are relative to "today" so the -20211 past-limit never bites.
 */

export interface LeaveMockEmployee {
  empId: number;
  empNumber: string;
  name: string;
  managerEmpId: number | null;
  hireDate: string;
  locationCode: string;
  active: boolean;
}

export const LEAVE_MOCK_EMPLOYEES: LeaveMockEmployee[] = [
  { empId: 1, empNumber: 'EMP-000001', name: 'JAMES RICHARDSON', managerEmpId: null, hireDate: '2010-01-04', locationCode: 'HQ', active: true },
  { empId: 21, empNumber: 'EMP-000021', name: 'JENNIFER PARK', managerEmpId: 1, hireDate: '2015-03-02', locationCode: 'HQ', active: true },
  { empId: 11, empNumber: 'EMP-000011', name: 'DAVID MARTINEZ', managerEmpId: 21, hireDate: '2022-06-01', locationCode: 'HQ', active: true },
  { empId: 12, empNumber: 'EMP-000012', name: 'EMILY JOHNSON', managerEmpId: 21, hireDate: addDays(isoToday(), -30), locationCode: 'HQ', active: true },
  { empId: 13, empNumber: 'EMP-000013', name: 'SARAH LEE', managerEmpId: 21, hireDate: '2019-09-16', locationCode: 'HQ', active: true },
];

export function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

function weekday(iso: string): number {
  return new Date(`${iso}T00:00:00Z`).getUTCDay();
}

/** Monday `weeks` weeks after the current week's Monday. */
export function mondayPlusWeeks(weeks: number, today = isoToday()): string {
  const dow = weekday(today);
  const monday = addDays(today, dow === 0 ? -6 : 1 - dow);
  return addDays(monday, weeks * 7);
}

export function currentYear(): number {
  return Number(isoToday().slice(0, 4));
}

interface Holiday {
  holidayName: string;
  holidayDate: string;
}

function holidaysFor(year: number): Holiday[] {
  return [
    { holidayName: "New Year's Day", holidayDate: `${year}-01-01` },
    { holidayName: 'Independence Day', holidayDate: `${year}-07-04` },
    { holidayName: 'Christmas Day', holidayDate: `${year}-12-25` },
  ];
}

/** BUG-05 fix: Saturday holidays observed on Friday, Sunday holidays on Monday. */
export function observedDate(holidayDate: string): string {
  const dow = weekday(holidayDate);
  if (dow === 6) return addDays(holidayDate, -1);
  if (dow === 0) return addDays(holidayDate, 1);
  return holidayDate;
}

export function mockBusinessDays(start: string, end: string): BusinessDays {
  const years = new Set<number>();
  for (let y = Number(start.slice(0, 4)); y <= Number(end.slice(0, 4)); y++) years.add(y);
  const observed: BusinessDaysHoliday[] = [...years]
    .flatMap(holidaysFor)
    .map((h) => ({ ...h, observedDate: observedDate(h.holidayDate) }))
    .filter((h) => h.observedDate >= start && h.observedDate <= end);
  const excluded = new Set(observed.map((h) => h.observedDate));
  let businessDays = 0;
  for (let d = start; d <= end; d = addDays(d, 1)) {
    const dow = weekday(d);
    if (dow !== 0 && dow !== 6 && !excluded.has(d)) businessDays += 1;
  }
  return { start, end, businessDays, holidays: observed };
}

const now = () => new Date().toISOString();

interface BalanceRow extends LeaveBalance {
  empId: number;
}

let requests: LeaveRequest[] = [];
let balances: BalanceRow[] = [];
let nextRequestId = 3100;
let nextBalanceId = 4100;

function withAvailable(row: Omit<BalanceRow, 'available'>): BalanceRow {
  return { ...row, available: round(row.openingBalance + row.accrued - row.used + row.adjustment - row.pending) };
}

function round(n: number) {
  return Math.round(n * 100) / 100;
}

function nameOf(empId: number | null) {
  return empId === null ? null : LEAVE_MOCK_EMPLOYEES.find((e) => e.empId === empId)?.name ?? `Employee #${empId}`;
}

function typeInfo(leaveTypes: LeaveTypeRef[], leaveTypeId: number) {
  const t = leaveTypes.find((x) => x.leaveTypeId === leaveTypeId);
  return { leaveTypeCode: t?.leaveTypeCode ?? `T${leaveTypeId}`, leaveTypeName: t?.leaveTypeName ?? `Type #${leaveTypeId}` };
}

interface SeedRequest {
  requestId: number;
  empId: number;
  leaveTypeId: number;
  startDate: string;
  endDate: string;
  halfDay?: boolean;
  halfDayPeriod?: HalfDayPeriod | null;
  status: LeaveRequestStatus;
  reason?: string | null;
  approverEmpId: number | null;
  approved?: boolean;
  approvalComments?: string | null;
}

function seed(leaveTypes: LeaveTypeRef[], s: SeedRequest): LeaveRequest {
  const created = `${addDays(s.startDate, -10)}T09:00:00Z`;
  const decided = s.status === 'PENDING' ? null : `${addDays(s.startDate, -8)}T10:00:00Z`;
  return {
    requestId: s.requestId,
    empId: s.empId,
    empName: nameOf(s.empId) ?? '',
    leaveTypeId: s.leaveTypeId,
    ...typeInfo(leaveTypes, s.leaveTypeId),
    startDate: s.startDate,
    endDate: s.endDate,
    totalDays: s.halfDay ? 0.5 : mockBusinessDays(s.startDate, s.endDate).businessDays,
    halfDay: s.halfDay ?? false,
    halfDayPeriod: s.halfDay ? s.halfDayPeriod ?? 'AM' : null,
    status: s.status,
    reason: s.reason ?? null,
    approverEmpId: s.approverEmpId,
    approverName: nameOf(s.approverEmpId),
    approvalDate: s.status === 'APPROVED' || s.status === 'REJECTED' ? decided : null,
    approvalComments: s.approvalComments ?? null,
    createdDate: created,
    modifiedDate: decided,
  };
}

export function resetLeaveState(leaveTypes: LeaveTypeRef[]) {
  const year = currentYear();
  const w2 = mondayPlusWeeks(2);
  const w3 = mondayPlusWeeks(3);
  const w4 = mondayPlusWeeks(4);
  const w5 = mondayPlusWeeks(5);
  const past = mondayPlusWeeks(-4);
  requests = [
    seed(leaveTypes, { requestId: 3001, empId: 11, leaveTypeId: 1, startDate: w2, endDate: addDays(w2, 2), status: 'PENDING', reason: 'Family trip', approverEmpId: 21 }),
    seed(leaveTypes, { requestId: 3002, empId: 11, leaveTypeId: 1, startDate: w4, endDate: w4, status: 'APPROVED', reason: 'Appointment', approverEmpId: 21, approvalComments: 'Enjoy' }),
    seed(leaveTypes, { requestId: 3003, empId: 11, leaveTypeId: 2, startDate: past, endDate: past, status: 'REJECTED', reason: null, approverEmpId: 21, approvalComments: 'Coverage needed' }),
    seed(leaveTypes, { requestId: 3004, empId: 11, leaveTypeId: 1, startDate: addDays(past, 7), endDate: addDays(past, 7), halfDay: true, halfDayPeriod: 'PM', status: 'CANCELLED', reason: 'Cancelled by employee', approverEmpId: 21 }),
    seed(leaveTypes, { requestId: 3005, empId: 12, leaveTypeId: 1, startDate: addDays(w3, 3), endDate: addDays(w3, 3), status: 'PENDING', reason: 'Moving day', approverEmpId: 21 }),
    seed(leaveTypes, { requestId: 3006, empId: 13, leaveTypeId: 1, startDate: addDays(w2, 3), endDate: addDays(w2, 4), status: 'APPROVED', reason: 'Conference', approverEmpId: 21 }),
    seed(leaveTypes, { requestId: 3007, empId: 21, leaveTypeId: 1, startDate: w5, endDate: addDays(w5, 4), status: 'PENDING', reason: 'Vacation', approverEmpId: 1 }),
  ];
  const bal = (balanceId: number, empId: number, leaveTypeId: number, opening: number, accrued: number, used: number, pending: number, carryover = 0) =>
    withAvailable({ balanceId, empId, leaveTypeId, ...typeInfo(leaveTypes, leaveTypeId), calendarYear: year, openingBalance: opening, accrued, used, adjustment: 0, pending, carryoverFromPrev: carryover });
  balances = [
    bal(4001, 11, 1, 5, 10, 2, 3, 5),
    bal(4002, 11, 2, 0, 5, 1, 0),
    bal(4003, 12, 1, 0, 2, 0, 1),
    bal(4004, 13, 1, 5, 10, 2, 0, 5),
    bal(4005, 21, 1, 5, 12, 0, 5, 5),
    bal(4006, 21, 2, 0, 6, 0, 0),
    bal(4007, 1, 1, 5, 15, 0, 0, 5),
  ];
  nextRequestId = 3100;
  nextBalanceId = 4100;
}

export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

export function getLeaveEmployee(empId: number) {
  return LEAVE_MOCK_EMPLOYEES.find((e) => e.empId === empId);
}

export function listLeaveRequests(filter: { empId: number; status?: LeaveRequestStatus[]; year?: number }) {
  return requests
    .filter((r) => r.empId === filter.empId)
    .filter((r) => !filter.status || filter.status.includes(r.status))
    .filter((r) => filter.year === undefined || Number(r.startDate.slice(0, 4)) === filter.year)
    .sort((a, b) => (a.createdDate < b.createdDate ? 1 : a.createdDate > b.createdDate ? -1 : b.requestId - a.requestId));
}

export function getLeaveRequest(requestId: number) {
  return requests.find((r) => r.requestId === requestId);
}

export function listBalances(empId: number, year: number): LeaveBalance[] {
  return balances
    .filter((b) => b.empId === empId && b.calendarYear === year)
    .sort((a, b) => a.leaveTypeName.localeCompare(b.leaveTypeName))
    .map((b) => { const { empId: _e, ...row } = b; void _e; return row; });
}

function balanceRow(empId: number, leaveTypeId: number, year: number) {
  return balances.find((b) => b.empId === empId && b.leaveTypeId === leaveTypeId && b.calendarYear === year);
}

/** QUIRK-01/02: no row → available 0 for the -20201 check. */
export function availableFor(empId: number, leaveTypeId: number, year: number): number {
  return balanceRow(empId, leaveTypeId, year)?.available ?? 0;
}

function adjustBalance(empId: number, leaveTypeId: number, year: number, delta: { pending?: number; used?: number }, leaveTypes: LeaveTypeRef[]) {
  let row = balanceRow(empId, leaveTypeId, year);
  if (!row) {
    row = withAvailable({ balanceId: nextBalanceId++, empId, leaveTypeId, ...typeInfo(leaveTypes, leaveTypeId), calendarYear: year, openingBalance: 0, accrued: 0, used: 0, adjustment: 0, pending: 0, carryoverFromPrev: 0 });
    balances.push(row);
  }
  const updated = withAvailable({ ...row, pending: round(row.pending + (delta.pending ?? 0)), used: round(row.used + (delta.used ?? 0)) });
  balances = balances.map((b) => (b.balanceId === row!.balanceId ? updated : b));
}

/** check_leave_overlap + BUG-06 (AM and PM half days on the same date do not overlap). */
export function overlaps(empId: number, startDate: string, endDate: string, halfDay: boolean, halfDayPeriod: HalfDayPeriod | null) {
  return requests.some((r) => {
    if (r.empId !== empId || (r.status !== 'PENDING' && r.status !== 'APPROVED')) return false;
    if (!(r.startDate <= endDate && r.endDate >= startDate)) return false;
    if (halfDay && r.halfDay && r.startDate === startDate && r.halfDayPeriod !== halfDayPeriod) return false;
    return true;
  });
}

export function createLeaveRequest(
  leaveTypes: LeaveTypeRef[],
  input: { empId: number; leaveTypeId: number; startDate: string; endDate: string; halfDay: boolean; halfDayPeriod: HalfDayPeriod | null; reason: string | null; totalDays: number },
): LeaveRequest {
  const emp = getLeaveEmployee(input.empId);
  const autoApprove = leaveTypes.find((t) => t.leaveTypeId === input.leaveTypeId)?.requiresApproval === false;
  const row: LeaveRequest = {
    requestId: nextRequestId++,
    empId: input.empId,
    empName: emp?.name ?? '',
    leaveTypeId: input.leaveTypeId,
    ...typeInfo(leaveTypes, input.leaveTypeId),
    startDate: input.startDate,
    endDate: input.endDate,
    totalDays: input.totalDays,
    halfDay: input.halfDay,
    halfDayPeriod: input.halfDay ? input.halfDayPeriod : null,
    status: autoApprove ? 'APPROVED' : 'PENDING',
    reason: input.reason,
    approverEmpId: emp?.managerEmpId ?? null,
    approverName: nameOf(emp?.managerEmpId ?? null),
    approvalDate: autoApprove ? now() : null,
    approvalComments: null,
    createdDate: now(),
    modifiedDate: null,
  };
  requests.push(row);
  adjustBalance(input.empId, input.leaveTypeId, Number(input.startDate.slice(0, 4)), autoApprove ? { used: input.totalDays } : { pending: input.totalDays }, leaveTypes);
  return row;
}

export function transition(leaveTypes: LeaveTypeRef[], row: LeaveRequest, status: LeaveRequestStatus, actor: { empId: number; asApprover: boolean }, comments: string | null) {
  const year = Number(row.startDate.slice(0, 4));
  if (status === 'APPROVED') adjustBalance(row.empId, row.leaveTypeId, year, { pending: -row.totalDays, used: row.totalDays }, leaveTypes);
  if (status === 'REJECTED') adjustBalance(row.empId, row.leaveTypeId, year, { pending: -row.totalDays }, leaveTypes);
  if (status === 'CANCELLED') {
    if (row.status === 'APPROVED') adjustBalance(row.empId, row.leaveTypeId, year, { used: -row.totalDays }, leaveTypes);
    else adjustBalance(row.empId, row.leaveTypeId, year, { pending: -row.totalDays }, leaveTypes);
  }
  const updated: LeaveRequest = {
    ...row,
    status,
    modifiedDate: now(),
    ...(actor.asApprover ? { approverEmpId: actor.empId, approverName: nameOf(actor.empId), approvalDate: now(), approvalComments: comments } : {}),
    ...(status === 'CANCELLED' ? { reason: comments ?? row.reason } : {}),
  };
  requests = requests.map((r) => (r.requestId === row.requestId ? updated : r));
  return updated;
}

export function pendingApprovals(approverEmpId: number): PendingLeaveApproval[] {
  return requests
    .filter((r) => r.approverEmpId === approverEmpId && r.status === 'PENDING')
    .sort((a, b) => (a.createdDate < b.createdDate ? -1 : a.createdDate > b.createdDate ? 1 : a.requestId - b.requestId))
    .map((r) => ({
      requestId: r.requestId,
      empId: r.empId,
      empNumber: getLeaveEmployee(r.empId)?.empNumber ?? '',
      empName: r.empName,
      leaveTypeName: r.leaveTypeName,
      startDate: r.startDate,
      endDate: r.endDate,
      totalDays: r.totalDays,
      halfDay: r.halfDay,
      halfDayPeriod: r.halfDayPeriod,
      reason: r.reason,
      createdDate: r.createdDate,
    }));
}

export function teamCalendar(managerEmpId: number, from: string, to: string): TeamCalendarEntry[] {
  const reports = new Set(LEAVE_MOCK_EMPLOYEES.filter((e) => e.managerEmpId === managerEmpId).map((e) => e.empId));
  return requests
    .filter((r) => reports.has(r.empId) && (r.status === 'APPROVED' || r.status === 'TAKEN') && r.startDate <= to && r.endDate >= from)
    .sort((a, b) => (a.startDate < b.startDate ? -1 : a.startDate > b.startDate ? 1 : a.empName.localeCompare(b.empName)))
    .map((r) => ({
      requestId: r.requestId,
      empId: r.empId,
      empName: r.empName,
      leaveTypeName: r.leaveTypeName,
      startDate: r.startDate,
      endDate: r.endDate,
      totalDays: r.totalDays,
      halfDay: r.halfDay,
      halfDayPeriod: r.halfDayPeriod,
      status: r.status as 'APPROVED' | 'TAKEN',
    }));
}
