import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { LeaveRequest } from '../../api/types';
import { formatDate } from '../../app/format';
import { BalancesGrid } from './BalancesGrid';
import { CancelRequestDialog } from './CancelRequestDialog';
import { LeaveStatusBadge } from './LeaveStatusBadge';
import { describeHalfDay, formatDays } from './leaveFormat';
import { balancesQueryKey, myRequestsQueryKey } from './queryKeys';

const CANCELLABLE = new Set<LeaveRequest['status']>(['PENDING', 'APPROVED']);

/** `LEAVE_REQUEST` block: caller's requests newest first + balances (COMPONENT_MAPPING.md §5). */
export function MyRequestsTab() {
  const qc = useQueryClient();
  const [cancelling, setCancelling] = useState<LeaveRequest | null>(null);
  const requests = useQuery({ queryKey: myRequestsQueryKey, queryFn: () => api.leave.listMyLeaveRequests() });
  const refresh = () => {
    setCancelling(null);
    void qc.invalidateQueries({ queryKey: myRequestsQueryKey });
    void qc.invalidateQueries({ queryKey: balancesQueryKey });
  };
  return (
    <section aria-labelledby="my-requests-title">
      <h2 id="my-requests-title">My Requests</h2>
      {requests.isPending ? <p role="status">Loading…</p> : requests.isError ? <p role="alert">Could not load leave requests.</p> : requests.data.length ? (
        <table className="grid" aria-label="My leave requests">
          <thead><tr><th>Leave type</th><th>Start</th><th>End</th><th>Days</th><th>Duration</th><th>Status</th><th>Approver</th><th>Reason</th><th /></tr></thead>
          <tbody>
            {requests.data.map((r) => (
              <tr key={r.requestId}>
                <td>{r.leaveTypeName}</td>
                <td>{formatDate(r.startDate)}</td>
                <td>{formatDate(r.endDate)}</td>
                <td>{formatDays(r.totalDays)}</td>
                <td>{describeHalfDay(r.halfDay, r.halfDayPeriod)}</td>
                <td><LeaveStatusBadge status={r.status} /></td>
                <td>{r.approverName ?? '—'}</td>
                <td>{r.reason ?? '—'}</td>
                <td>
                  {CANCELLABLE.has(r.status) && (
                    <button type="button" onClick={() => setCancelling(r)} aria-label={`Cancel request ${r.requestId}`}>
                      Cancel Request
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p>No leave requests</p>}
      <BalancesGrid />
      {cancelling && <CancelRequestDialog request={cancelling} onClose={() => setCancelling(null)} onCancelled={refresh} />}
    </section>
  );
}
