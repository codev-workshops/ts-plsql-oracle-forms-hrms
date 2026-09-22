import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import { formatDate, formatMoney } from '../../app/format';
import { useErrorHandler } from '../../app/useErrorHandler';
import { errorLabel, humanize, payslipKey } from './payrollShared';

/**
 * Payslip (COMPONENT_MAPPING.md §4) → `GET /api/payroll/runs/{runId}/payslips/{empId}`.
 * `{empId}` is the payslip subject from the route; the acting user is the JWT subject.
 */
export function PayslipView() {
  const { runId: runParam, empId: empParam } = useParams();
  const runId = Number(runParam);
  const empId = Number(empParam);
  const { handleError } = useErrorHandler();
  const result = useQuery({ queryKey: payslipKey(runId, empId), queryFn: () => api.payroll.getPayslip(runId, empId), retry: false });

  if (result.isPending) return <p role="status">Loading payslip…</p>;
  if (result.isError) {
    const handled = handleError(result.error, { toast: false });
    return (
      <div data-testid="payslip-error">
        <p role="alert" className="banner banner-warning">{errorLabel(handled.code, handled.message)}</p>
        <Link to={`/payroll/runs/${runId}/details`}>← Pay details</Link>
      </div>
    );
  }

  const s = result.data;
  return (
    <article className="payslip" data-testid="payslip" aria-labelledby="payslip-title">
      <div className="toolbar">
        <Link to={`/payroll/runs/${runId}/details`}>← Pay details</Link>
        <button type="button" onClick={() => window.print()}>Print</button>
      </div>
      <h2 id="payslip-title">Payslip · {s.empName} ({s.empNumber})</h2>
      <p>
        {s.departmentName ?? '—'} · {s.jobTitle ?? '—'} · {s.periodName} ({formatDate(s.periodStartDate)} – {formatDate(s.periodEndDate)}) · pay date {formatDate(s.payDate)} ·{' '}
        <span className={`badge badge-${s.runStatus}`}>{humanize(s.runStatus)}</span>
      </p>
      <table className="grid" aria-label="Payslip summary">
        <thead>
          <tr><th>Item</th><th>This period</th><th>Year to date</th></tr>
        </thead>
        <tbody>
          <tr><td>Gross pay</td><td data-testid="payslip-gross">{formatMoney(s.grossPay)}</td><td>{formatMoney(s.ytdGross)}</td></tr>
          <tr><td>Federal tax</td><td>{formatMoney(s.federalTax)}</td><td rowSpan={4}>{formatMoney(s.ytdTaxes)}</td></tr>
          <tr><td>State tax</td><td>{formatMoney(s.stateTax)}</td></tr>
          <tr><td>Social security</td><td>{formatMoney(s.socialSecurity)}</td></tr>
          <tr><td>Medicare</td><td>{formatMoney(s.medicare)}</td></tr>
          <tr><td>Other deductions</td><td>{formatMoney(s.otherDeductions)}</td><td>{formatMoney(s.ytdDeductions)}</td></tr>
          <tr><td>Total deductions</td><td>{formatMoney(s.totalDeductions)}</td><td>—</td></tr>
          <tr><th>Net pay</th><th data-testid="payslip-net">{formatMoney(s.netPay)}</th><th>{formatMoney(s.ytdNet)}</th></tr>
        </tbody>
      </table>
      <h3>Lines</h3>
      <table className="grid" aria-label="Payslip lines">
        <thead>
          <tr><th>Element</th><th>Type</th><th>Rate</th><th>Amount</th></tr>
        </thead>
        <tbody>
          {s.lines.map((l) => (
            <tr key={l.detailId}><td>{l.elementCode}</td><td>{humanize(l.elementType)}</td><td>{l.rate ?? '—'}</td><td>{formatMoney(l.amount)}</td></tr>
          ))}
        </tbody>
      </table>
    </article>
  );
}
