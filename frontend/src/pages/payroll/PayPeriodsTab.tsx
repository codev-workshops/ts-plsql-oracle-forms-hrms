import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { PayPeriod, PayPeriodListQuery, PayPeriodSort, PeriodStatus } from '../../api/types';
import { formatDate } from '../../app/format';
import { useErrorHandler } from '../../app/useErrorHandler';
import { useToast } from '../../app/ToastContext';
import { getDto } from '../../validation/schema';
import { humanize, payPeriodsKey, usePayrollWrite } from './payrollShared';

const PAGE_SIZE = 20;
const STATUSES = getDto('PayPeriodListQuery').fields.status.values as PeriodStatus[];
const SORTS: { value: PayPeriodSort; label: string }[] = [
  { value: 'periodStartDate,desc', label: 'Start date (newest first)' },
  { value: 'periodStartDate,asc', label: 'Start date (oldest first)' },
  { value: 'payDate,desc', label: 'Pay date (newest first)' },
  { value: 'periodName,asc', label: 'Period name' },
];

/** `PAY_PERIODS` block (HRMS_PAYROLL.xml) → `GET /api/payroll/periods`; Close → `POST …/close`. */
export function PayPeriodsTab() {
  const { canApprove } = usePayrollWrite();
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<PeriodStatus | ''>('');
  const [sort, setSort] = useState<PayPeriodSort>('periodStartDate,desc');
  const [page, setPage] = useState(0);
  const [confirmClose, setConfirmClose] = useState<PayPeriod | null>(null);

  const params: PayPeriodListQuery = { ...(status ? { status } : {}), sort, page, size: PAGE_SIZE };
  const result = useQuery({ queryKey: payPeriodsKey(params), queryFn: () => api.payroll.listPayPeriods(params), placeholderData: (prev) => prev });

  const close = useMutation({
    mutationFn: (p: PayPeriod) => api.payroll.closePayPeriod(p.periodId),
    onSuccess: (p) => {
      push({ kind: 'success', message: `Pay period ${p.periodName} closed.` });
      setConfirmClose(null);
      void queryClient.invalidateQueries({ queryKey: ['payroll', 'periods'] });
    },
    onError: (error) => {
      handleError(error);
      setConfirmClose(null);
    },
  });

  const data = result.data;
  return (
    <div data-testid="pay-periods-tab">
      <form className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label="Pay period filters">
        <label htmlFor="periodStatus">Status</label>
        <select id="periodStatus" value={status} onChange={(e) => { setStatus(e.target.value as PeriodStatus | ''); setPage(0); }}>
          <option value="">All</option>
          {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </select>
        <label htmlFor="periodSort">Sort</label>
        <select id="periodSort" value={sort} onChange={(e) => { setSort(e.target.value as PayPeriodSort); setPage(0); }}>
          {SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
        {data && data.totalPages > 1 && (
          <span className="toolbar-pagination">
            <button type="button" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>Previous</button>
            <span>Page {data.page + 1} of {data.totalPages}</span>
            <button type="button" onClick={() => setPage((p) => p + 1)} disabled={data.page + 1 >= data.totalPages}>Next</button>
          </span>
        )}
      </form>

      {result.isPending && <p role="status">Loading pay periods…</p>}
      {result.isError && <p role="alert" className="field-error">Could not load pay periods.</p>}
      {data && (
        <table className="grid" aria-label="Pay periods">
          <thead>
            <tr>
              <th>Period</th>
              <th>Frequency</th>
              <th>Start</th>
              <th>End</th>
              <th>Pay date</th>
              <th>Status</th>
              <th>Runs</th>
              <th>Latest run</th>
              <th><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {data.content.length === 0 && (
              <tr><td colSpan={9}>No pay periods match the filter.</td></tr>
            )}
            {data.content.map((p) => (
              <tr key={p.periodId} data-testid={`period-row-${p.periodId}`}>
                <td>{p.periodName}</td>
                <td>{humanize(p.payFrequency)}</td>
                <td>{formatDate(p.periodStartDate)}</td>
                <td>{formatDate(p.periodEndDate)}</td>
                <td>{formatDate(p.payDate)}</td>
                <td><span className={`badge badge-${p.status}`}>{humanize(p.status)}</span></td>
                <td>{p.runCount}</td>
                <td>{p.latestRunStatus ? <span className={`badge badge-${p.latestRunStatus}`}>{humanize(p.latestRunStatus)}</span> : '—'}</td>
                <td className="actions">
                  <Link to={`/payroll/periods/${p.periodId}/runs`} data-testid={`period-runs-${p.periodId}`}>Runs</Link>
                  {canApprove && p.status !== 'CLOSED' && (
                    <button type="button" onClick={() => setConfirmClose(p)} disabled={close.isPending}>Close period</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {confirmClose && (
        <div className="dialog-overlay">
          <div role="dialog" aria-modal="true" aria-labelledby="close-period-title" className="dialog">
            <h3 id="close-period-title">Close pay period</h3>
            <p>
              Close <strong>{confirmClose.periodName}</strong>? No further payroll runs can be created for a closed period.
            </p>
            <div className="actions">
              <button type="button" onClick={() => close.mutate(confirmClose)} disabled={close.isPending}>Close period</button>
              <button type="button" onClick={() => setConfirmClose(null)} disabled={close.isPending}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
