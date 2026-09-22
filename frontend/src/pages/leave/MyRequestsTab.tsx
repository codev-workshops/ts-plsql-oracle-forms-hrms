import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { LeaveRequest } from '../../api/types';
import { formatDate } from '../../app/format';
import { useErrorHandler } from '../../app/useErrorHandler';
import { CancelRequestDialog } from './CancelRequestDialog';
import { LeaveStatusBadge } from './LeaveStatusBadge';
import { formatDays, leaveKeys } from './leaveKeys';

/** HRMS_LEAVE "My Requests" block + balances (COMPONENT_MAPPING.md §5). Identity = JWT. */
/** Cancellable statuses per contract `cancelLeaveRequest` (-20204 otherwise). */
const CANCELLABLE = new Set<LeaveRequest['status']>(['PENDING', 'APPROVED']);

export function MyRequestsTab() {
  const qc = useQueryClient();
  const { handleError } = useErrorHandler();
  const [cancelling, setCancelling] = useState<LeaveRequest | null>(null);
  const requests = useQuery({ queryKey: leaveKeys.mine, queryFn: () => api.leave.listMyLeaveRequests() });
  const balances = useQuery({ queryKey: leaveKeys.balances, queryFn: () => api.leave.getMyLeaveBalances() });

  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: leaveKeys.mine }), qc.invalidateQueries({ queryKey: leaveKeys.balances })]);

  return (
    <div className="my-requests-tab">
      <h2>Balances</h2>
      {balances.isLoading && <p>Loading balances…</p>}
      {balances.isError && <p role="alert">{handleError(balances.error, { toast: false }).message}</p>}
      {balances.data && (
        <table aria-label="Leave balances">
          <thead>
            <tr><th>Leave type</th><th>Year</th><th>Opening</th><th>Accrued</th><th>Used</th><th>Adjustment</th><th>Pending</th><th>Available</th></tr>
          </thead>
          <tbody>
            {balances.data.length === 0 && <tr><td colSpan={8}>No balances for this year.</td></tr>}
            {balances.data.map((b) => (
              <tr key={b.balanceId}>
                <td>{b.leaveTypeName}</td><td>{b.calendarYear}</td><td>{b.openingBalance}</td><td>{b.accrued}</td><td>{b.used}</td><td>{b.adjustment}</td><td>{b.pending}</td>
                <td data-testid={`available-${b.leaveTypeCode}`}><strong>{b.available}</strong></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>My requests</h2>
      {requests.isLoading && <p>Loading requests…</p>}
      {requests.isError && <p role="alert">{handleError(requests.error, { toast: false }).message}</p>}
      {requests.data && (
        <table aria-label="My leave requests">
          <thead>
            <tr><th>Type</th><th>From</th><th>To</th><th>Days</th><th>Status</th><th>Approver</th><th>Reason</th><th>Actions</th></tr>
          </thead>
          <tbody>
            {requests.data.length === 0 && <tr><td colSpan={8}>No leave requests yet.</td></tr>}
            {requests.data.map((r) => (
              <tr key={r.requestId}>
                <td>{r.leaveTypeName}</td>
                <td>{formatDate(r.startDate)}</td>
                <td>{formatDate(r.endDate)}</td>
                <td>{formatDays(r.totalDays, r.halfDay, r.halfDayPeriod)}</td>
                <td><LeaveStatusBadge status={r.status} /></td>
                <td>{r.approverName ?? '—'}</td>
                <td>{r.reason ?? ''}</td>
                <td>
                  <button type="button" onClick={() => setCancelling(r)} disabled={!CANCELLABLE.has(r.status)} aria-label={`Cancel request ${r.requestId}`}>
                    Cancel Request
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {cancelling && (
        <CancelRequestDialog
          request={cancelling}
          onClose={() => setCancelling(null)}
          onCancelled={async () => {
            setCancelling(null);
            await refresh();
          }}
        />
      )}
    </div>
  );
}
