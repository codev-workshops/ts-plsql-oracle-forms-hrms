import type { LeaveRequestStatus } from '../../api/types';

export function LeaveStatusBadge({ status }: { status: LeaveRequestStatus }) {
  return (
    <span className={`badge badge-${status}`} data-status={status}>
      {status}
    </span>
  );
}
