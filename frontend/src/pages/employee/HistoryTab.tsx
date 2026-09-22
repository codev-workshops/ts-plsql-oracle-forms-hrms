import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { EmployeeHistoryEntry } from '../../api/types';
import { formatDate, formatMoney } from '../../app/format';
import { employeeHistoryKey, humanize } from './employeeShared';

function change(from: string | null, to: string | null) {
  if (from === null && to === null) return '—';
  if (from === null) return to;
  if (to === null) return from;
  return from === to ? from : `${from} → ${to}`;
}

function money(v: string | null) {
  return v === null ? null : formatMoney(v);
}

/** `EMPLOYEE_HISTORY` (HRMS_EMPLOYEE.fmb "Employment History" tab) → `GET /api/employees/{id}/history`. */
export function HistoryTab({ empId }: { empId: number }) {
  const history = useQuery({ queryKey: employeeHistoryKey(empId), queryFn: () => api.employees.listEmployeeHistory(empId) });
  return (
    <section aria-labelledby="history-title">
      <h3 id="history-title">Employment history</h3>
      {history.isPending ? (
        <p role="status">Loading…</p>
      ) : history.isError ? (
        <p role="alert">Could not load employment history.</p>
      ) : history.data.length ? (
        <table className="grid" aria-label="Employment history">
          <thead>
            <tr><th>Effective</th><th>Change</th><th>Department</th><th>Job</th><th>Manager</th><th>Salary</th><th>Location</th><th>Reason</th><th>Comments</th></tr>
          </thead>
          <tbody>
            {history.data.map((h: EmployeeHistoryEntry) => (
              <tr key={h.histId}>
                <td>{formatDate(h.effectiveDate)}</td>
                <td>{humanize(h.changeType)}</td>
                <td>{change(h.oldDeptName, h.newDeptName)}</td>
                <td>{change(h.oldJobTitle, h.newJobTitle)}</td>
                <td>{change(h.oldManagerName, h.newManagerName)}</td>
                <td>{change(money(h.oldSalary), money(h.newSalary))}</td>
                <td>{change(h.oldLocation, h.newLocation)}</td>
                <td>{h.reasonCode ?? '—'}</td>
                <td>{h.comments ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>No history recorded.</p>
      )}
    </section>
  );
}
