import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { PendingLeaveApproval } from '../../api/types';
import { formatDate } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { formatDays, leaveKeys } from './leaveKeys';
import { RejectRequestDialog } from './RejectRequestDialog';

/** HRMS_LEAVE "Approvals" block: VW_PENDING_APPROVALS filtered to the JWT approver (COMPONENT_MAPPING.md §5). */
export function ApprovalsTab() {
  const qc = useQueryClient();
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const [rejecting, setRejecting] = useState<PendingLeaveApproval | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const pending = useQuery({ queryKey: leaveKeys.pending, queryFn: () => api.leave.listPendingLeaveApprovals() });

  const refresh = () => qc.invalidateQueries({ queryKey: ['leave'] });

  const approve = async (row: PendingLeaveApproval) => {
    setBusyId(row.requestId);
    try {
      await api.leave.approveLeaveRequest(row.requestId, {});
      push({ kind: 'success', message: `Approved leave for ${row.empName}` });
      await refresh();
    } catch (error) {
      handleError(error);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="approvals-tab">
      <h2>Pending approvals</h2>
      {pending.isLoading && <p>Loading approvals…</p>}
      {pending.isError && <p role="alert">{handleError(pending.error, { toast: false }).message}</p>}
      {pending.data && (
        <table aria-label="Pending leave approvals">
          <thead>
            <tr><th>Employee</th><th>Type</th><th>From</th><th>To</th><th>Days</th><th>Reason</th><th>Submitted</th><th>Actions</th></tr>
          </thead>
          <tbody>
            {pending.data.length === 0 && <tr><td colSpan={8}>Nothing waiting for your approval.</td></tr>}
            {pending.data.map((r) => (
              <tr key={r.requestId}>
                <td>{r.empName} ({r.empNumber})</td>
                <td>{r.leaveTypeName}</td>
                <td>{formatDate(r.startDate)}</td>
                <td>{formatDate(r.endDate)}</td>
                <td>{formatDays(r.totalDays, r.halfDay, r.halfDayPeriod)}</td>
                <td>{r.reason ?? ''}</td>
                <td>{formatDate(r.createdDate)}</td>
                <td className="actions">
                  <button type="button" onClick={() => approve(r)} disabled={busyId !== null}>Approve</button>
                  <button type="button" onClick={() => setRejecting(r)} disabled={busyId !== null}>Reject</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {rejecting && (
        <RejectRequestDialog
          approval={rejecting}
          onClose={() => setRejecting(null)}
          onRejected={async () => {
            setRejecting(null);
            await refresh();
          }}
        />
      )}
    </div>
  );
}
