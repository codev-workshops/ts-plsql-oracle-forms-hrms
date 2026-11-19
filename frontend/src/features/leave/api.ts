import { api } from '../../api/client';

export interface LeaveType {
  leaveTypeId: number;
  code: string;
  name: string;
  accrual: boolean;
  requiresApproval: boolean;
  minTenureDays: number;
}

export interface LeaveBalance {
  leaveTypeId: number;
  leaveTypeCode: string;
  leaveTypeName: string;
  year: number;
  openingBalance: number;
  accrued: number;
  used: number;
  adjustment: number;
  pending: number;
  available: number;
}

export interface LeaveRequest {
  requestId: number;
  empId: number;
  employeeName: string;
  leaveTypeId: number;
  leaveTypeName: string;
  startDate: string;
  endDate: string;
  totalDays: number;
  halfDay: boolean;
  halfDayPeriod?: string | null;
  status: string;
  reason?: string | null;
  approverName?: string | null;
  approvalComments?: string | null;
}

export interface SubmitLeave {
  leaveTypeId: number;
  startDate: string;
  endDate: string;
  halfDay: boolean;
  halfDayPeriod?: 'AM' | 'PM';
  reason?: string;
}

export const leaveApi = {
  types: () => api<LeaveType[]>('/leave/types'),
  balances: (year?: number) => api<LeaveBalance[]>(`/leave/balances${year ? `?year=${year}` : ''}`),
  myRequests: () => api<LeaveRequest[]>('/leave/requests'),
  submit: (body: SubmitLeave) => api<LeaveRequest>('/leave/requests', { method: 'POST', body }),
  cancel: (id: number, reason: string) =>
    api<LeaveRequest>(`/leave/requests/${id}/cancel`, { method: 'POST', body: { reason } }),
  approvals: () => api<LeaveRequest[]>('/leave/approvals'),
  approve: (id: number, comments?: string) =>
    api<LeaveRequest>(`/leave/requests/${id}/approve`, { method: 'POST', body: { comments } }),
  reject: (id: number, comments: string) =>
    api<LeaveRequest>(`/leave/requests/${id}/reject`, { method: 'POST', body: { comments } }),
  teamCalendar: (startDate: string, endDate: string) =>
    api<LeaveRequest[]>(`/leave/team-calendar?startDate=${startDate}&endDate=${endDate}`),
};
