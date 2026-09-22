import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { EmployeeDetail } from '../../api/types';
import { formatDate } from '../../app/format';
import { normaliseError } from '../../app/useErrorHandler';
import { useEmployeeModule } from './EmployeeModuleContext';
import { employeeKeys } from './queryKeys';
import { SalaryDialog } from './SalaryDialog';
import { formatMoney } from './salaryFormat';

/**
 * `SALARY` block → `GET /api/employees/{id}/salary` + `/salary/history` (salary-module).
 * -20104 (no active record) is a normal state for terminated / freshly hired employees.
 */
export function SalaryTab({ employee }: { employee: EmployeeDetail }) {
  const { writable, canChangeSalary, canViewSalary } = useEmployeeModule();
  const [dialogOpen, setDialogOpen] = useState(false);
  const scoped = canViewSalary(employee.id);
  const current = useQuery({ queryKey: employeeKeys.salary(employee.id), queryFn: () => api.employees.getCurrentSalary(employee.id), enabled: scoped, retry: false });
  const history = useQuery({ queryKey: employeeKeys.salaryHistory(employee.id), queryFn: () => api.employees.listSalaryHistory(employee.id), enabled: scoped });

  if (!scoped) return <p role="note">Salary information is restricted to payroll and HR.</p>;

  const noActive = current.isError && normaliseError(current.error).apiError?.code === '-20104';
  const record = current.data ?? null;
  const canChange = writable && canChangeSalary && employee.employmentStatus === 'ACTIVE';

  return (
    <section aria-labelledby="salary-title">
      <div className="toolbar">
        <h3 id="salary-title">Current salary</h3>
        {canChange && (
          <button type="button" onClick={() => setDialogOpen(true)}>
            Change salary
          </button>
        )}
      </div>
      {current.isPending ? (
        <p role="status">Loading…</p>
      ) : record ? (
        <dl className="detail-grid" data-testid="current-salary">
          <dt>Base salary</dt>
          <dd>
            {formatMoney(record.baseSalary, record.currencyCode)}
            {record.outOfGradeBand && (
              <span className="badge badge-warning" data-testid="out-of-band-badge">
                Out of grade band
              </span>
            )}
          </dd>
          <dt>Basis / frequency</dt>
          <dd>
            {record.salaryBasis} · {record.payFrequency}
          </dd>
          <dt>Effective</dt>
          <dd>{formatDate(record.effectiveDate)}</dd>
          <dt>Reason</dt>
          <dd>{record.changeReason}</dd>
          <dt>Change</dt>
          <dd>{record.changePct !== null && record.changePct !== undefined ? `${Number(record.changePct) >= 0 ? '+' : ''}${record.changePct}%` : '—'}</dd>
        </dl>
      ) : noActive ? (
        <p role="note">No active salary record.</p>
      ) : (
        <p role="alert">Could not load salary.</p>
      )}

      <h3>Salary history</h3>
      {history.isPending ? (
        <p role="status">Loading…</p>
      ) : history.isError ? (
        <p role="alert">Could not load salary history.</p>
      ) : history.data.length ? (
        <table className="grid" aria-label="Salary history">
          <thead>
            <tr>
              <th>Effective</th>
              <th>End</th>
              <th>Base salary</th>
              <th>Basis</th>
              <th>Frequency</th>
              <th>Change</th>
              <th>Reason</th>
              <th>Band</th>
            </tr>
          </thead>
          <tbody>
            {history.data.map((s) => (
              <tr key={s.salaryId}>
                <td>{formatDate(s.effectiveDate)}</td>
                <td>{s.endDate ? formatDate(s.endDate) : '—'}</td>
                <td>{formatMoney(s.baseSalary, s.currencyCode)}</td>
                <td>{s.salaryBasis}</td>
                <td>{s.payFrequency}</td>
                <td>{s.changePct !== null && s.changePct !== undefined ? `${s.changePct}%` : '—'}</td>
                <td>{s.changeReason}</td>
                <td>{s.outOfGradeBand ? 'Out of band' : 'In band'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>No salary history.</p>
      )}

      {dialogOpen && (
        <SalaryDialog
          employee={employee}
          current={record}
          onClose={() => setDialogOpen(false)}
          onDone={() => {
            setDialogOpen(false);
            void current.refetch();
            void history.refetch();
          }}
        />
      )}
    </section>
  );
}
