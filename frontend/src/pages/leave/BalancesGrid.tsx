import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import { formatDays } from './leaveFormat';
import { balancesQueryKey } from './queryKeys';

/** `LEAVE_BALANCE` block (VW_LEAVE_SUMMARY projection); `available` is the table formula (VAL-05). */
export function BalancesGrid() {
  const balances = useQuery({ queryKey: balancesQueryKey, queryFn: () => api.leave.getMyLeaveBalances() });
  return (
    <section aria-labelledby="balances-title">
      <h3 id="balances-title">Leave balances</h3>
      {balances.isPending ? <p role="status">Loading balances…</p> : balances.isError ? <p role="alert">Could not load balances.</p> : balances.data.length ? (
        <table className="grid" aria-label="Leave balances">
          <thead><tr><th>Leave type</th><th>Year</th><th>Opening</th><th>Accrued</th><th>Used</th><th>Adjustment</th><th>Pending</th><th>Carryover</th><th>Available</th></tr></thead>
          <tbody>
            {balances.data.map((b) => (
              <tr key={b.balanceId}>
                <td>{b.leaveTypeName}</td>
                <td>{b.calendarYear}</td>
                <td>{formatDays(b.openingBalance)}</td>
                <td>{formatDays(b.accrued)}</td>
                <td>{formatDays(b.used)}</td>
                <td>{formatDays(b.adjustment)}</td>
                <td>{formatDays(b.pending)}</td>
                <td>{formatDays(b.carryoverFromPrev)}</td>
                <td data-testid={`available-${b.leaveTypeCode}`}>{formatDays(b.available)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p>No balances</p>}
    </section>
  );
}
