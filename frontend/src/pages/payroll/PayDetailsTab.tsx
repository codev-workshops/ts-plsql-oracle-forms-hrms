import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { DetailStatus, PayrollDetailListQuery } from '../../api/types';
import { formatMoney } from '../../app/format';
import { fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';
import { errorLabel, humanize, payrollDetailsKey } from './payrollShared';

const PAGE_SIZE = 50;
const STATUSES = getDto('PayrollDetailListQuery').fields.status.values as DetailStatus[];
const schema = zodFor('PayrollDetailListQuery');

/** `PAYROLL_DETAILS` block (HRMS_PAYROLL.xml) → `GET /api/payroll/runs/{runId}/details`. */
export function PayDetailsTab() {
  const runId = Number(useParams().runId);
  const [empIdDraft, setEmpIdDraft] = useState('');
  const [status, setStatus] = useState<DetailStatus | ''>('');
  const [empId, setEmpId] = useState<number | undefined>(undefined);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [page, setPage] = useState(0);

  const params: PayrollDetailListQuery = { ...(empId ? { empId } : {}), ...(status ? { status } : {}), page, size: PAGE_SIZE };
  const result = useQuery({ queryKey: payrollDetailsKey(runId, params), queryFn: () => api.payroll.listPayrollDetails(runId, params), placeholderData: (prev) => prev });

  const apply = (event: FormEvent) => {
    event.preventDefault();
    const candidate: Record<string, unknown> = { page: 0, size: PAGE_SIZE };
    if (empIdDraft.trim() !== '') candidate.empId = Number(empIdDraft);
    if (status) candidate.status = status;
    const parsed = schema.safeParse(candidate);
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setPage(0);
    setEmpId(empIdDraft.trim() === '' ? undefined : Number(empIdDraft));
  };

  const data = result.data;
  const errorRows = data?.content.filter((d) => d.status === 'ERROR').length ?? 0;
  return (
    <div data-testid="pay-details-tab">
      <form className="toolbar" onSubmit={apply} noValidate aria-label="Pay detail filters">
        <Link to="/payroll">← Pay periods</Link>
        <h2 style={{ margin: 0 }}>Run #{runId}</h2>
        <label htmlFor="detailEmpId">Employee id</label>
        <input id="detailEmpId" inputMode="numeric" value={empIdDraft} onChange={(e) => setEmpIdDraft(e.target.value)} aria-invalid={errors.empId ? true : undefined} />
        {errors.empId && <span role="alert" className="field-error">{errors.empId}</span>}
        <label htmlFor="detailStatus">Status</label>
        <select id="detailStatus" value={status} onChange={(e) => setStatus(e.target.value as DetailStatus | '')}>
          <option value="">Current</option>
          {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </select>
        <button type="submit">Apply</button>
        {data && data.totalPages > 1 && (
          <span className="toolbar-pagination">
            <button type="button" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>Previous</button>
            <span>Page {data.page + 1} of {data.totalPages}</span>
            <button type="button" onClick={() => setPage((p) => p + 1)} disabled={data.page + 1 >= data.totalPages}>Next</button>
          </span>
        )}
      </form>

      {errorRows > 0 && (
        <p role="status" className="banner banner-warning" data-testid="detail-error-banner">
          {errorRows} employee{errorRows === 1 ? '' : 's'} on this page could not be calculated – see the Error column.
        </p>
      )}
      {result.isPending && <p role="status">Loading pay details…</p>}
      {result.isError && <p role="alert" className="field-error">Could not load pay details.</p>}
      {data && (
        <table className="grid" aria-label="Pay details">
          <thead>
            <tr>
              <th>Employee</th>
              <th>Element</th>
              <th>Type</th>
              <th>Hours</th>
              <th>Rate</th>
              <th>Amount</th>
              <th>YTD</th>
              <th>Status</th>
              <th>Error</th>
              <th><span className="sr-only">Payslip</span></th>
            </tr>
          </thead>
          <tbody>
            {data.content.length === 0 && <tr><td colSpan={10}>No pay details match the filter.</td></tr>}
            {data.content.map((d) => (
              <tr key={d.detailId} data-testid={`detail-row-${d.detailId}`} className={d.status === 'ERROR' ? 'detail-error' : undefined}>
                <td>{d.empNumber}</td>
                <td>{d.elementCode}</td>
                <td>{humanize(d.elementType)}</td>
                <td>{d.hoursWorked ?? '—'}</td>
                <td>{d.rate ?? '—'}</td>
                <td>{formatMoney(d.amount)}</td>
                <td>{d.ytdAmount ? formatMoney(d.ytdAmount) : '—'}</td>
                <td><span className={`badge badge-${d.status}`}>{humanize(d.status)}</span></td>
                <td>{d.status === 'ERROR' ? errorLabel(d.errorCode, d.errorMessage) : ''}</td>
                <td>
                  {d.status === 'CALCULATED' && d.elementType === 'EARNING' && (
                    <Link to={`/payroll/runs/${runId}/payslips/${d.empId}`} data-testid={`payslip-link-${d.empId}`}>Payslip</Link>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
