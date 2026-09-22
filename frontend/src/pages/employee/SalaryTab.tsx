import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { EmployeeDetail } from '../../api/types';
import { formatDate, formatMoney } from '../../app/format';
import { normaliseError } from '../../app/useErrorHandler';
import { humanize, salaryCurrentKey, salaryHistoryKey } from './employeeShared';

/**
 * `SALARY` block (HRMS_EMPLOYEE.fmb "Job & Compensation") → salary-module
 * `GET /api/employees/{id}/salary` + `/salary/history`; the change itself is a dialog.
 */
export function SalaryTab({ employee, onChangeSalary }: { employee: EmployeeDetail; onChangeSalary?: () => void }) {
  const current = useQuery({ queryKey: salaryCurrentKey(employee.id), queryFn: () => api.salary.getCurrentSalary(employee.id), retry: false });
  const history = useQuery({ queryKey: salaryHistoryKey(employee.id), queryFn: () => api.salary.listSalaryHistory(employee.id) });
  const noActive = current.isError && normaliseError(current.error).apiError?.code === '-20104';
  const forbidden = current.isError && normaliseError(current.error).status === 403;

  return (
    <section aria-labelledby="salary-title">
      <div className="toolbar">
        <h3 id="salary-title">Salary</h3>
        {onChangeSalary && <button type="button" onClick={onChangeSalary}>Change salary</button>}
      </div>
      {current.isPending ? (
        <p role="status">Loading…</p>
      ) : current.isSuccess ? (
        <dl className="record-summary" aria-label="Current salary">
          <dt>Base salary</dt><dd data-testid="current-salary">{formatMoney(current.data.baseSalary)} {current.data.currencyCode}</dd>
          <dt>Basis</dt><dd>{humanize(current.data.salaryBasis)} · {humanize(current.data.payFrequency)}</dd>
          <dt>Effective</dt><dd>{formatDate(current.data.effectiveDate)}</dd>
          <dt>Grade</dt><dd>{employee.gradeCode ?? '—'}</dd>
          <dt>Reason</dt><dd>{current.data.changeReason ?? '—'}</dd>
        </dl>
      ) : noActive ? (
        <p role="status">No active salary record.</p>
      ) : forbidden ? (
        <p role="status">You are not permitted to view this employee's salary.</p>
      ) : (
        <p role="alert">Could not load the current salary.</p>
      )}
      {current.isSuccess && current.data.outOfGradeBand && (
        <p role="status" className="banner banner-warning">
          Current salary is outside the {employee.gradeCode ?? 'job'} grade band.
        </p>
      )}

      <h4>Salary history</h4>
      {history.isPending ? (
        <p role="status">Loading…</p>
      ) : history.isError ? (
        normaliseError(history.error).status === 403 ? null : <p role="alert">Could not load salary history.</p>
      ) : history.data.length ? (
        <table className="grid" aria-label="Salary history">
          <thead>
            <tr><th>Effective</th><th>End</th><th>Base salary</th><th>Change %</th><th>Reason</th><th>Grade band</th></tr>
          </thead>
          <tbody>
            {history.data.map((s) => (
              <tr key={s.salaryId}>
                <td>{formatDate(s.effectiveDate)}</td>
                <td>{s.endDate ? formatDate(s.endDate) : 'current'}</td>
                <td>{formatMoney(s.baseSalary)} {s.currencyCode}</td>
                <td>{s.changePct === null ? '—' : `${s.changePct}%`}</td>
                <td>{s.changeReason ?? '—'}</td>
                <td>{s.outOfGradeBand ? 'Out of band' : 'In band'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>No salary history.</p>
      )}
    </section>
  );
}
