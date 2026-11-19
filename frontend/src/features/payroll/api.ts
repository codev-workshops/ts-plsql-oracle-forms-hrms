import { api, ApiError, tokenStore } from '../../api/client';

export const runTypes = ['REGULAR', 'OFF_CYCLE', 'BONUS', 'CORRECTION'] as const;
export type RunType = (typeof runTypes)[number];
export type RunAction = 'calculate' | 'approve' | 'reverse';
export type PeriodFrequency = 'MONTHLY' | 'BIWEEKLY';
export type PeriodFilter = '' | 'OPEN' | 'CLOSED' | 'PROCESSING';
type RunStatus = 'PENDING' | 'CALCULATING' | 'CALCULATED' | 'APPROVED' | 'PAID' | 'REVERSED' | 'ERROR';

export interface PayPeriod {
  periodId: number;
  periodName: string;
  frequency: string;
  startDate: string;
  endDate: string;
  payDate: string;
  status: string;
}

export interface PayrollRun {
  runId: number;
  periodId: number;
  runType: string;
  status: RunStatus;
  employeeCount: number;
  totalGross: number;
  totalDeductions: number;
  totalNet: number;
  calculatedAt?: string;
  approvedBy?: string;
}

export interface PayrollDetail {
  detailId: number;
  empId: number;
  elementId?: number;
  elementCode?: string;
  elementType: string;
  amount: number;
  status: string;
  errorMessage?: string;
}

export interface Payslip {
  runId: number;
  empId: number;
  employeeName: string;
  period: PayPeriod;
  lines: PayrollDetail[];
  gross: number;
  deductions: number;
  taxes: number;
  net: number;
  ytdGross: number;
  ytdTaxes: number;
  ytdNet: number;
}

const prefix = '/payroll';

export const payroll = {
  periods: (year: number) => api<PayPeriod[]>(`${prefix}/periods?year=${year}`),
  createPeriods: (year: number, frequency: PeriodFrequency) =>
    api<PayPeriod[]>(`${prefix}/periods`, { method: 'POST', body: { year, frequency } }),
  closePeriod: (periodId: number) =>
    api<PayPeriod>(`${prefix}/periods/${periodId}/close`, { method: 'POST' }),
  runs: (periodId?: number) =>
    api<PayrollRun[]>(`${prefix}/runs${periodId ? `?periodId=${periodId}` : ''}`),
  createRun: (periodId: number, runType: RunType) =>
    api<PayrollRun>(`${prefix}/runs`, { method: 'POST', body: { periodId, runType } }),
  run: (runId: number) => api<PayrollRun>(`${prefix}/runs/${runId}`),
  runAction: (runId: number, action: RunAction) =>
    api<PayrollRun>(`${prefix}/runs/${runId}/${action}`, { method: 'POST' }),
  details: (runId: number) => api<PayrollDetail[]>(`${prefix}/runs/${runId}/details`),
  payslip: (runId: number, empId: number) => api<Payslip>(`${prefix}/payslips/${runId}/${empId}`),
};

export async function downloadPayroll(path: string, fallbackName: string) {
  // The shared client parses JSON. These contract endpoints return text attachments.
  const token = tokenStore.get();
  const response = await fetch(`/api/v1${prefix}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (response.status === 401) {
    await api('/auth/me'); // Let the shared auth provider handle an expired session.
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiError(response.status, body ?? { code: String(response.status), message: 'Download failed' });
  }
  const name = /filename="([^"]+)"/i.exec(response.headers.get('Content-Disposition') ?? '')?.[1] ?? fallbackName;
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function payrollError(error: unknown) {
  if (error instanceof ApiError) return `${error.message} (${error.code})`;
  return error instanceof Error ? error.message : 'Payroll request failed';
}
