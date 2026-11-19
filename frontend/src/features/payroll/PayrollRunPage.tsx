import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  downloadPayroll, payroll, payrollError, type PayrollDetail, type PayrollRun, type RunAction,
} from './api';
import { DetailTable, PayrollStatus } from './display';
import { money } from './format';

export function PayrollRunPage({ runId, canEdit }: { runId: number; canEdit: boolean }) {
  const [run, setRun] = useState<PayrollRun>();
  const [lines, setLines] = useState<PayrollDetail[]>([]);
  const [selectedEmployee, setSelectedEmployee] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    Promise.all([payroll.run(runId), payroll.details(runId)])
      .then(([current, details]) => {
        if (!active) return;
        setRun(current);
        setLines(details);
      })
      .catch((failure: unknown) => { if (active) setError(payrollError(failure)); });
    return () => { active = false; };
  }, [runId]);

  async function perform(action: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (failure) {
      setError(payrollError(failure));
    } finally {
      setBusy(false);
    }
  }

  function transition(action: RunAction) {
    if (action === 'reverse' && !window.confirm(`Reverse payroll run #${runId}?`)) return;
    void perform(async () => {
      const result = await payroll.runAction(runId, action);
      setRun(result);
      setLines(await payroll.details(runId));
    });
  }

  const employeeIds = [...new Set(lines.map((line) => line.empId))];
  const visibleLines = selectedEmployee ? lines.filter((line) => String(line.empId) === selectedEmployee) : lines;

  return (
    <>
      <p><Link to="/payroll">Back to pay periods and runs</Link></p>
      {error && <p className="error" role="alert">{error}</p>}
      {!run ? !error && <p>Loading payroll run…</p> : (
        <>
          <section className="card">
            <div className="row payroll-section-heading">
              <h2>Payroll run #{run.runId}</h2><PayrollStatus status={run.status} />
            </div>
            <p>Period #{run.periodId} · {run.runType} · {run.employeeCount} employees</p>
            {run.approvedBy && <p>Approved by {run.approvedBy}</p>}
            <dl className="payroll-totals">
              <div><dt>Gross pay</dt><dd>{money(run.totalGross)}</dd></div>
              <div><dt>Total deductions</dt><dd>{money(run.totalDeductions)}</dd></div>
              <div><dt>Net pay</dt><dd>{money(run.totalNet)}</dd></div>
            </dl>
            <div className="row payroll-actions">
              {canEdit && run.status === 'PENDING' && (
                <button type="button" disabled={busy} onClick={() => transition('calculate')}>Calculate payroll</button>
              )}
              {canEdit && run.status === 'CALCULATED' && (
                <button type="button" disabled={busy} onClick={() => transition('approve')}>Approve payroll</button>
              )}
              {canEdit && run.status !== 'REVERSED' && (
                <button type="button" disabled={busy} onClick={() => transition('reverse')}>Reverse run</button>
              )}
              <button type="button" disabled={busy} onClick={() => void perform(
                () => downloadPayroll(`/runs/${runId}/register.csv`, `PAY_REGISTER_${runId}.csv`))}>Download pay register</button>
              <button type="button" disabled={busy} onClick={() => void perform(
                () => downloadPayroll(`/runs/${runId}/gl-journal`, `GL_JOURNAL_${runId}.dat`))}>Download GL journal</button>
            </div>
          </section>
          <section className="card">
            <h2>Payroll details</h2>
            {lines.length === 0 ? <p>No payroll detail lines for this run.</p> : (
              <>
                <div className="row payroll-controls">
                  <label>Filter by employee
                    <select value={selectedEmployee} onChange={(event) => setSelectedEmployee(event.target.value)}>
                      <option value="">All employees</option>
                      {employeeIds.map((empId) => <option key={empId} value={empId}>Employee #{empId}</option>)}
                    </select>
                  </label>
                  {selectedEmployee && visibleLines.some((line) => line.status === 'CALCULATED') && (
                    <Link to={`/payroll/payslips?runId=${runId}&empId=${selectedEmployee}`}>View employee payslip</Link>
                  )}
                </div>
                <DetailTable lines={visibleLines} showEmployee />
              </>
            )}
          </section>
        </>
      )}
    </>
  );
}
