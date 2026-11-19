import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  downloadPayroll, payroll, payrollError, runTypes,
  type PayPeriod, type PayrollRun, type PeriodFilter, type PeriodFrequency, type RunType,
} from './api';
import { PayrollStatus } from './display';
import { money } from './format';

export function PayrollOverview({ canEdit }: { canEdit: boolean }) {
  const navigate = useNavigate();
  const [year, setYear] = useState(new Date().getFullYear());
  const [selectedYear, setSelectedYear] = useState(year);
  const [status, setStatus] = useState<PeriodFilter>('');
  const [frequency, setFrequency] = useState<PeriodFrequency>('MONTHLY');
  const [periods, setPeriods] = useState<PayPeriod[]>([]);
  const [runs, setRuns] = useState<PayrollRun[]>([]);
  const [periodFilter, setPeriodFilter] = useState<number>();
  const [runPeriod, setRunPeriod] = useState('');
  const [runType, setRunType] = useState<RunType>('REGULAR');
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    Promise.all([payroll.periods(selectedYear), payroll.runs(periodFilter)])
      .then(([nextPeriods, nextRuns]) => {
        if (!active) return;
        setPeriods(nextPeriods);
        setRuns(nextRuns);
      })
      .catch((failure: unknown) => { if (active) setError(payrollError(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [selectedYear, periodFilter, refresh]);

  async function perform(action: () => Promise<void>) {
    setBusy(true);
    setError('');
    setMessage('');
    try {
      await action();
    } catch (failure) {
      setError(payrollError(failure));
    } finally {
      setBusy(false);
    }
  }

  function filterPeriods(event: FormEvent) {
    event.preventDefault();
    setSelectedYear(year);
    setPeriodFilter(undefined);
    setRunPeriod('');
    setError('');
    setRefresh((value) => value + 1);
  }

  function generatePeriods() {
    void perform(async () => {
      const generated = await payroll.createPeriods(selectedYear, frequency);
      setMessage(`${generated.length} ${frequency.toLowerCase()} periods available for ${selectedYear}.`);
      setRefresh((value) => value + 1);
    });
  }

  const runPeriods = periods.filter((period) => period.status !== 'CLOSED');
  const visiblePeriods = periods.filter((period) => !status || period.status === status);

  return (
    <>
      {error && <p className="error" role="alert">{error}</p>}
      {message && <p className="success" role="status">{message}</p>}
      <section className="card">
        <h2>Pay periods</h2>
        <form className="row payroll-controls" onSubmit={filterPeriods}>
          <label>Period year
            <input type="number" required min={1} max={9999} value={year || ''}
              onChange={(event) => setYear(event.currentTarget.valueAsNumber)} />
          </label>
          <label>Period status
            <select value={status} onChange={(event) => setStatus(event.target.value as PeriodFilter)}>
              <option value="">All statuses</option>
              <option value="OPEN">Open</option>
              <option value="PROCESSING">Processing</option>
              <option value="CLOSED">Closed</option>
            </select>
          </label>
          <button type="submit" disabled={busy}>Load year</button>
        </form>
        {canEdit && (
          <div className="payroll-controls row">
            <label>Period frequency
              <select value={frequency} onChange={(event) => setFrequency(event.target.value as PeriodFrequency)}>
                <option value="MONTHLY">Monthly</option>
                <option value="BIWEEKLY">Biweekly</option>
              </select>
            </label>
            <button type="button" disabled={busy || loading} onClick={generatePeriods}>
              Generate {selectedYear} periods
            </button>
          </div>
        )}
        {loading ? <p>Loading payroll data…</p> : visiblePeriods.length > 0 ? (
          <div className="payroll-table">
            <table aria-label="Pay periods">
              <thead><tr><th>Period</th><th>Frequency</th><th>Start</th><th>End</th><th>Pay date</th><th>Status</th><th>Actions</th></tr></thead>
              <tbody>
                {visiblePeriods.map((period) => (
                  <tr key={period.periodId}>
                    <th scope="row">{period.periodName}</th><td>{period.frequency}</td>
                    <td>{period.startDate}</td><td>{period.endDate}</td><td>{period.payDate}</td>
                    <td><PayrollStatus status={period.status} /></td>
                    <td className="payroll-actions">
                      <a href="#payroll-runs" onClick={() => setPeriodFilter(period.periodId)}>View runs</a>
                      {canEdit && period.status !== 'CLOSED' && (
                        <button type="button" disabled={busy} onClick={() => {
                          if (!window.confirm(`Close pay period ${period.periodName}?`)) return;
                          void perform(async () => {
                            const closed = await payroll.closePeriod(period.periodId);
                            setPeriods((items) => items.map((item) => item.periodId === closed.periodId ? closed : item));
                            if (Number(runPeriod) === closed.periodId) setRunPeriod('');
                            setMessage(`${closed.periodName} closed.`);
                          });
                        }}>Close</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p>No pay periods match these filters.</p>}
      </section>

      <section className="card" id="payroll-runs">
        <div className="row payroll-section-heading">
          <h2>Payroll runs</h2>
          {periodFilter && (
            <button type="button" onClick={() => setPeriodFilter(undefined)}>Show all runs</button>
          )}
        </div>
        {periodFilter && <p>Period {periods.find((period) => period.periodId === periodFilter)?.periodName ?? periodFilter}</p>}
        {canEdit && (
          <form className="row payroll-controls" onSubmit={(event) => {
            event.preventDefault();
            void perform(async () => {
              const run = await payroll.createRun(Number(runPeriod), runType);
              navigate(`/payroll/runs/${run.runId}`);
            });
          }}>
            <label>Run period
              <select value={runPeriod} required onChange={(event) => setRunPeriod(event.target.value)}>
                <option value="">Select a period</option>
                {runPeriods.map((period) => <option key={period.periodId} value={period.periodId}>{period.periodName}</option>)}
              </select>
            </label>
            <label>Run type
              <select value={runType} onChange={(event) => setRunType(event.target.value as RunType)}>
                {runTypes.map((type) => <option key={type}>{type}</option>)}
              </select>
            </label>
            <button type="submit" disabled={busy || loading || !runPeriod}>Create run</button>
          </form>
        )}
        {!loading && (runs.length > 0 ? (
          <div className="payroll-table">
            <table aria-label="Payroll runs">
              <thead><tr><th>Run</th><th>Period</th><th>Type</th><th>Status</th><th>Employees</th><th>Gross</th><th>Net</th></tr></thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.runId}>
                    <th scope="row"><Link to={`/payroll/runs/${run.runId}`}>#{run.runId}</Link></th>
                    <td>{periods.find((period) => period.periodId === run.periodId)?.periodName ?? run.periodId}</td>
                    <td>{run.runType}</td><td><PayrollStatus status={run.status} /></td>
                    <td>{run.employeeCount}</td><td>{money(run.totalGross)}</td><td>{money(run.totalNet)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p>No payroll runs found.</p>)}
        <button type="button" disabled={busy} onClick={() => void perform(
          () => downloadPayroll('/exports/benefits', 'BENEFITS.txt'))}>
          Download benefits feed
        </button>
      </section>
    </>
  );
}
