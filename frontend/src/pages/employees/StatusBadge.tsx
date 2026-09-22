import type { EmploymentStatus } from '../../api/types';

const LABELS: Record<EmploymentStatus, string> = { ACTIVE: 'Active', ON_LEAVE: 'On leave', SUSPENDED: 'Suspended', TERMINATED: 'Terminated' };

export function StatusBadge({ status }: { status: EmploymentStatus }) {
  return <span className={`badge badge-${status}`}>{LABELS[status]}</span>;
}
