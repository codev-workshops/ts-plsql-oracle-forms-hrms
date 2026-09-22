import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { PendingLeaveApproval } from '../../api/types';
import { formatDate, formatDateTime } from '../../app/format';
import { ApprovalDialog, type ApprovalAction } from './ApprovalDialog';
import { describeHalfDay, formatDays } from './leaveFormat';
import { pendingApprovalsQueryKey, teamCalendarQueryKey } from './queryKeys';

/** `VW_PENDING_APPROVALS` (APPROVAL_TYPE = 'LEAVE') for the caller as designated approver. */
export function ApprovalsTab() {
  const qc = useQueryClient();
  const [dialog, setDialog] = useState<{ action: ApprovalAction; request: PendingLeaveApproval } | null>(null);
  const pending = useQuery({ queryKey: pendingApprovalsQueryKey, queryFn: () => api.leave.listPendingLeaveApprovals() });
  const done = () => {
    setDialog(null);
    void qc.invalidateQueries({ queryKey: pendingApprovalsQueryKey });
    void qc.invalidateQueries({ queryKey: teamCalendarQueryKey });
  };
  return (
    <section aria-labelledby="approvals-title">
      <h2 id="approvals-title">Approvals</h2>
      {pending.isPending ? <p role="status">Loading…</p> : pending.isError ? <p role="alert">Could not load pending approvals.</p> : pending.data.length ? (
        <table className="grid" aria-label="Pending leave approvals">
          <thead><tr><th>Employee</th><th>Emp #</th><th>Leave type</th><th>Start</th><th>End</th><th>Days</th><th>Duration</th><th>Reason</th><th>Requested</th><th /></tr></thead>
          <tbody>
            {pending.data.map((r) => (
              <tr key={r.requestId}>
                <td>{r.empName}</td>
                <td>{r.empNumber}</td>
                <td>{r.leaveTypeName}</td>
                <td>{formatDate(r.startDate)}</td>
                <td>{formatDate(r.endDate)}</td>
                <td>{formatDays(r.totalDays)}</td>
                <td>{describeHalfDay(r.halfDay, r.halfDayPeriod)}</td>
                <td>{r.reason ?? '—'}</td>
                <td>{formatDateTime(r.createdDate)}</td>
                <td>
                  <button type="button" onClick={() => setDialog({ action: 'approve', request: r })} aria-label={`Approve request ${r.requestId}`}>Approve</button>
                  <button type="button" onClick={() => setDialog({ action: 'reject', request: r })} aria-label={`Reject request ${r.requestId}`}>Reject</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p>No pending approvals</p>}
      {dialog && <ApprovalDialog action={dialog.action} request={dialog.request} onClose={() => setDialog(null)} onDone={done} />}
    </section>
  );
}
