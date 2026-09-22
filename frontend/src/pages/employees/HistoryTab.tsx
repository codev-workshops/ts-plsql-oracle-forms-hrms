import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { EmployeeHistoryEntry } from '../../api/types';
import { formatDate } from '../../app/format';
import { employeeKeys } from './queryKeys';
import { formatMoney } from './salaryFormat';

function describe(h: EmployeeHistoryEntry): string {
  const parts: string[] = [];
  if (h.oldDeptName || h.newDeptName) parts.push(`Dept: ${h.oldDeptName ?? '—'} → ${h.newDeptName ?? '—'}`);
  if (h.oldJobTitle || h.newJobTitle) parts.push(`Job: ${h.oldJobTitle ?? '—'} → ${h.newJobTitle ?? '—'}`);
  if (h.oldManagerName || h.newManagerName) parts.push(`Manager: ${h.oldManagerName ?? '—'} → ${h.newManagerName ?? '—'}`);
  if (h.oldSalary || h.newSalary) parts.push(`Salary: ${h.oldSalary ? formatMoney(h.oldSalary) : '—'} → ${h.newSalary ? formatMoney(h.newSalary) : '—'}`);
  if (h.oldLocation || h.newLocation) parts.push(`Location: ${h.oldLocation ?? '—'} → ${h.newLocation ?? '—'}`);
  return parts.join(' · ') || '—';
}

/** `EMP_HISTORY` block → `GET /api/employees/{id}/history` (VW_EMPLOYEE_HISTORY semantics, newest first). */
export function HistoryTab({ empId }: { empId: number }) {
  const history = useQuery({ queryKey: employeeKeys.history(empId), queryFn: () => api.employees.listEmployeeHistory(empId) });
  if (history.isPending) return <p role="status">Loading…</p>;
  if (history.isError) return <p role="alert">Could not load employment history.</p>;
  if (!history.data.length) return <p>No history recorded.</p>;
  return (
    <table className="grid" aria-label="Employment history">
      <thead>
        <tr>
          <th>Effective</th>
          <th>Change</th>
          <th>Details</th>
          <th>Reason</th>
          <th>Comments</th>
          <th>Recorded by</th>
        </tr>
      </thead>
      <tbody>
        {history.data.map((h) => (
          <tr key={h.histId}>
            <td>{formatDate(h.effectiveDate)}</td>
            <td>{h.changeType.replace(/_/g, ' ')}</td>
            <td>{describe(h)}</td>
            <td>{h.reasonCode ?? '—'}</td>
            <td>{h.comments ?? '—'}</td>
            <td>{h.createdBy}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
