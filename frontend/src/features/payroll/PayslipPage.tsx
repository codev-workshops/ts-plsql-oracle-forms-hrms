import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { payroll, payrollError, type Payslip } from './api';
import { DetailTable } from './display';
import { money, positiveId } from './format';

export function PayslipPage({ callerId, canViewAll }: { callerId: number; canViewAll: boolean }) {
  const [search, setSearch] = useSearchParams();
  const submittedRunId = positiveId(search.get('runId'));
  const submittedEmpId = canViewAll ? positiveId(search.get('empId')) ?? callerId : callerId;
  const [runId, setRunId] = useState(search.get('runId') ?? '');
  const [empId, setEmpId] = useState(String(submittedEmpId));
  const [payslip, setPayslip] = useState<Payslip>();
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setRunId(submittedRunId ? String(submittedRunId) : '');
    setEmpId(String(submittedEmpId));
  }, [submittedRunId, submittedEmpId]);

  useEffect(() => {
    setPayslip(undefined);
    setError('');
    setLoading(Boolean(submittedRunId));
    if (!submittedRunId) return;
    let active = true;
    payroll.payslip(submittedRunId, submittedEmpId)
      .then((slip) => { if (active) setPayslip(slip); })
      .catch((failure: unknown) => { if (active) setError(payrollError(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [submittedRunId, submittedEmpId, refresh]);

  function findPayslip(event: FormEvent) {
    event.preventDefault();
    if (!positiveId(runId) || !positiveId(empId)) {
      setError('Run ID and employee ID must be positive integers.');
      return;
    }
    setSearch(canViewAll ? { runId, empId } : { runId });
    setRefresh((value) => value + 1);
  }

  return (
    <section className="card">
      <h2>{canViewAll ? 'Payslip lookup' : 'My payslip'}</h2>
      <form className="row payroll-controls" onSubmit={findPayslip}>
        <label>Run ID
          <input type="number" required min={1} value={runId} onChange={(event) => setRunId(event.target.value)} />
        </label>
        {canViewAll && (
          <label>Employee ID
            <input type="number" required min={1} value={empId} onChange={(event) => setEmpId(event.target.value)} />
          </label>
        )}
        <button type="submit" disabled={loading}>Find payslip</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {loading && <p>Loading payslip…</p>}
      {payslip && payslip.runId === submittedRunId && payslip.empId === submittedEmpId && (
        <>
          <h3>{payslip.employeeName}</h3>
          <p>{payslip.period.periodName} · Pay date {payslip.period.payDate}</p>
          <dl className="payroll-totals">
            <div><dt>Gross pay</dt><dd>{money(payslip.gross)}</dd></div>
            <div><dt>Total deductions</dt><dd>{money(payslip.deductions)}</dd></div>
            <div><dt>Taxes included</dt><dd>{money(payslip.taxes)}</dd></div>
            <div><dt>Net pay</dt><dd>{money(payslip.net)}</dd></div>
          </dl>
          <DetailTable lines={payslip.lines} />
          <h3>Year to date</h3>
          <dl className="payroll-totals">
            <div><dt>YTD gross</dt><dd>{money(payslip.ytdGross)}</dd></div>
            <div><dt>YTD taxes</dt><dd>{money(payslip.ytdTaxes)}</dd></div>
            <div><dt>YTD net</dt><dd>{money(payslip.ytdNet)}</dd></div>
          </dl>
        </>
      )}
    </section>
  );
}
