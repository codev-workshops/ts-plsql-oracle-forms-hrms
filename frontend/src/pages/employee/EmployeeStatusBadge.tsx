import type { EmploymentStatus } from '../../api/types';

export function EmployeeStatusBadge({ status }: { status: EmploymentStatus }) {
  return <span className={`badge badge-${status}`}>{status.replace('_', ' ')}</span>;
}
