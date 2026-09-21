import type { CycleStatus, GoalStatus, ReviewStatus } from '../../api/types';

export function StatusBadge({ status }: { status: CycleStatus | ReviewStatus | GoalStatus }) {
  return (
    <span className={`badge badge-${status}`} data-status={status}>
      {status.replaceAll('_', ' ')}
    </span>
  );
}
