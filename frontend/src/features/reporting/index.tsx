import { useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { useAuth } from '../../auth/AuthContext';
import { downloadReport, fetchReport, type Report, type ReportKind, type ReportQuery } from './api';
import './reporting.css';

interface Filter {
  name: string;
  label: string;
  type: 'date' | 'integer';
  required?: boolean;
  defaultValue?: string;
}

const AS_OF: Filter = { name: 'asOf', label: 'As of', type: 'date' };
const DEPT: Filter = { name: 'deptId', label: 'Department ID', type: 'integer' };
const DATES: Filter[] = [
  { name: 'startDate', label: 'Start date', type: 'date', required: true },
  { name: 'endDate', label: 'End date', type: 'date', required: true },
  DEPT,
];

const REPORTS: Record<ReportKind, { title: string; filters: Filter[] }> = {
  headcount: {
    title: 'Headcount',
    filters: [AS_OF, DEPT, { name: 'locationId', label: 'Location ID', type: 'integer' }],
  },
  compensation: {
    title: 'Compensation',
    filters: [DEPT, { name: 'gradeId', label: 'Grade ID', type: 'integer' }],
  },
  turnover: { title: 'Turnover', filters: DATES },
  'new-hires': { title: 'New hires', filters: DATES },
  'leave-utilization': {
    title: 'Leave utilization',
    filters: [{ name: 'year', label: 'Year', type: 'integer', required: true,
      defaultValue: String(new Date().getFullYear()) }, DEPT],
  },
  'payroll-summary': {
    title: 'Payroll summary',
    filters: [{ name: 'periodId', label: 'Pay period ID', type: 'integer', required: true }],
  },
  eeo: { title: 'EEO compliance', filters: [AS_OF] },
};

const LABELS: Record<string, string> = {
  dept_id: 'Department ID',
  dept_name: 'Department',
  location_id: 'Location ID',
  grade_id: 'Grade ID',
  emp_count: 'Employees',
  emp_number: 'Employee number',
  emp_name: 'Employee',
  eeo_category: 'EEO category',
  ft_count: 'Full-time',
  pt_count: 'Part-time',
  contract_count: 'Contract',
  current_hc: 'Current headcount',
  total_fed_tax: 'Federal tax',
  total_ss: 'Social Security',
  turnover_pct: 'Turnover (%)',
  utilization_pct: 'Utilization (%)',
  female_pct: 'Female (%)',
  compa_ratio: 'Compa-ratio (%)',
};

function columnLabel(column: string) {
  return LABELS[column] ?? column.charAt(0).toUpperCase() + column.slice(1).replace(/_/g, ' ');
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) return `${error.message} (${error.code})`;
  return error instanceof Error ? error.message : 'Could not load the report.';
}

export function ReportingPage() {
  const { user } = useAuth();
  const [kind, setKind] = useState<ReportKind>('headcount');

  return (
    <section>
      <h1>Reports</h1>
      {(user?.gradeLevel ?? 0) < 5 ? (
        <p role="alert">You do not have permission to view reports.</p>
      ) : (
        <>
          <div className="card reporting-picker">
            <label>
              Report type
              <select value={kind} onChange={(event) => setKind(event.target.value as ReportKind)}>
                {Object.entries(REPORTS).map(([key, { title }]) => (
                  <option key={key} value={key}>{title}</option>
                ))}
              </select>
            </label>
          </div>
          <ReportPanel key={kind} kind={kind} />
        </>
      )}
    </section>
  );
}

function ReportPanel({ kind }: { kind: ReportKind }) {
  const { logout } = useAuth();
  const [result, setResult] = useState<{ report: Report; query: ReportQuery } | null>(null);
  const [busy, setBusy] = useState<'report' | 'csv' | null>(null);
  const [error, setError] = useState('');
  const { title, filters } = REPORTS[kind];

  async function run(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const params = new URLSearchParams();
    for (const { name } of filters) {
      const value = String(values.get(name) ?? '').trim();
      if (value) params.set(name, value);
    }
    const start = params.get('startDate');
    const end = params.get('endDate');
    setError('');
    setResult(null);
    if (start && end && end < start) {
      setError('Start date must be before or equal to end date.');
      return;
    }
    setBusy('report');
    const query = { kind, params };
    try {
      setResult({ query, report: await fetchReport(query) });
    } catch (error) {
      setError(errorMessage(error));
    } finally {
      setBusy(null);
    }
  }

  async function exportCsv(query: ReportQuery) {
    setError('');
    setBusy('csv');
    try {
      await downloadReport(query);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void logout().catch(() => undefined);
      setError(errorMessage(error));
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <form onSubmit={run} className="card">
        <h2>{title}</h2>
        <div className="row">
          {filters.map(({ name, label, type, required, defaultValue }) => (
            <label key={name}>
              {label}
              <input name={name} type={type === 'date' ? 'date' : 'text'} required={required}
                inputMode={type === 'integer' ? 'numeric' : undefined}
                pattern={type === 'integer' ? '[0-9]+' : undefined} defaultValue={defaultValue} />
            </label>
          ))}
        </div>
        {kind === 'headcount' && <p>Run without IDs to see all departments and locations. If As of is blank, the report uses today.</p>}
        <button type="submit" disabled={busy !== null}>Run report</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
      {busy && <p role="status">{busy === 'report' ? 'Running report…' : 'Preparing CSV…'}</p>}
      {result && (
        <section className="card">
          <h3>{result.report.name} results</h3>
          <p>Generated {result.report.generatedAt.replace('T', ' ')}</p>
          <button type="button" disabled={busy !== null} onClick={() => void exportCsv(result.query)}>
            Download CSV
          </button>
          {result.report.rows.length === 0 ? <p>No rows match these filters.</p> : (
            <div className="reporting-table" role="region" aria-label={`${result.report.name} results`} tabIndex={0}>
              <table aria-label={result.report.name}>
                <thead>
                  <tr>{result.report.columns.map((column) => (
                    <th scope="col" key={column}>{columnLabel(column)}</th>
                  ))}</tr>
                </thead>
                <tbody>
                  {result.report.rows.map((row, index) => (
                    <tr key={index}>{result.report.columns.map((column) => (
                      <td key={column}>{row[column] == null ? '—' : String(row[column])}</td>
                    ))}</tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </>
  );
}
