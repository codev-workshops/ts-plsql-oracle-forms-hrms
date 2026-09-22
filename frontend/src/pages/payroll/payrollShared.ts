import type { PayPeriodListQuery, PayrollDetailListQuery, PayrollRunListQuery } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { useModuleWritable } from '../../app/ModuleFlagsContext';

export const payPeriodsKey = (params: PayPeriodListQuery) => ['payroll', 'periods', params] as const;
export const payrollRunsKey = (periodId: number, params: PayrollRunListQuery) => ['payroll', 'runs', periodId, params] as const;
export const payrollRunStatusKey = (runId: number) => ['payroll', 'run-status', runId] as const;
export const payrollDetailsKey = (runId: number, params: PayrollDetailListQuery) => ['payroll', 'details', runId, params] as const;
export const payslipKey = (runId: number, empId: number) => ['payroll', 'payslip', runId, empId] as const;

/** Poll cadence for `GET …/status` while a run is CALCULATING (contracts/p4-payroll/openapi.yaml). */
export const CALCULATION_POLL_MS = 2000;

/**
 * Payroll write controls (create / calculate / approve / reverse / close, `includeBank`) exist
 * only for `PAYROLL:APPROVE` holders (OpenAPI `x-preauthorize`) and only while the proxy reports
 * `payroll=NEW` – the page itself is only mounted at that flag (CUTOVER_PLAN.md §8.4).
 */
export function usePayrollWrite() {
  const writable = useModuleWritable('payroll');
  const { hasAuthority } = useAuth();
  const canApprove = writable && hasAuthority('PAYROLL:APPROVE');
  return { readOnly: !writable, canApprove };
}

export function humanize(code: string | null | undefined): string {
  if (!code) return '—';
  return code.charAt(0) + code.slice(1).toLowerCase().replace(/_/g, ' ');
}

/** Legacy `-20xxx` codes and framework codes both travel as strings; show them verbatim. */
export function errorLabel(code: string | null | undefined, message: string | null | undefined): string {
  if (!code && !message) return '';
  return code ? `${code}: ${message ?? ''}`.trim() : (message ?? '');
}

/** Trigger a browser download of a text blob (no-op when the platform has no object URLs, e.g. jsdom). */
export function saveTextFile(filename: string, text: string, mime = 'text/csv;charset=utf-8') {
  if (typeof URL.createObjectURL !== 'function') return;
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
