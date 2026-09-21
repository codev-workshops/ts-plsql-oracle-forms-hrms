import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { CycleStatus, ReviewCycle } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { formatDate } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { Toolbar } from '../../app/Toolbar';
import { CycleDialog } from './CycleDialog';
import { StatusBadge } from './StatusBadge';

const statuses: CycleStatus[] = ['DRAFT', 'OPEN', 'IN_PROGRESS', 'CALIBRATION', 'CLOSED'];
const sorts = ['cycleYear,desc', 'cycleYear,asc', 'startDate,desc', 'startDate,asc'] as const;

export function ReviewCyclesTab() {
  const { hasAuthority } = useAuth();
  const { push } = useToast();
  const { handleError } = useErrorHandler();
  const queryClient = useQueryClient();
  const [checked, setChecked] = useState<CycleStatus[]>(['DRAFT', 'OPEN']);
  const [sort, setSort] = useState<(typeof sorts)[number]>('cycleYear,desc');
  const [dialog, setDialog] = useState<{ cycle?: ReviewCycle } | null>(null);
  const params = { status: checked.join(','), sort };
  const query = useQuery({ queryKey: ['performance', 'cycles', params], queryFn: () => api.performance.listCycles(params) });
  const admin = hasAuthority('PERFORMANCE:ADMIN');
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['performance', 'cycles'] });
  const action = useMutation<ReviewCycle | import('../../api/types').GenerateReviewsResult, unknown, { cycleId: number; type: 'open' | 'close' | 'generate' }>({
    mutationFn: ({ cycleId, type }: { cycleId: number; type: 'open' | 'close' | 'generate' }) =>
      type === 'open' ? api.performance.openCycle(cycleId) : type === 'close' ? api.performance.closeCycle(cycleId) : api.performance.generateReviews(cycleId),
    onSuccess: (result, variables) => {
      invalidate();
      if (variables.type === 'open') push({ kind: 'success', message: 'Cycle opened' });
      else if (variables.type === 'close') push({ kind: 'success', message: 'Cycle closed' });
      else {
        const generated = result as import('../../api/types').GenerateReviewsResult;
        push({ kind: 'success', message: `Generated ${generated.generated} reviews (${generated.skipped} skipped)` });
      }
    },
    onError: (error) => handleError(error),
  });

  const toggle = (status: CycleStatus) =>
    setChecked((old) => old.includes(status) ? old.filter((value) => value !== status) : [...old, status]);

  return (
    <section aria-labelledby="cycles-title">
      <h2 id="cycles-title">Review Cycles</h2>
      <div className="filters">
        <fieldset>
          <legend>Status</legend>
          {statuses.map((status) => (
            <label key={status}>
              <input type="checkbox" checked={checked.includes(status)} onChange={() => toggle(status)} />
              {status.replaceAll('_', ' ')}
            </label>
          ))}
        </fieldset>
        <label htmlFor="cycle-sort">Sort</label>
        <select id="cycle-sort" value={sort} onChange={(event) => setSort(event.target.value as (typeof sorts)[number])}>
          {sorts.map((value) => <option key={value} value={value}>{value}</option>)}
        </select>
      </div>
      {admin && <Toolbar onNew={() => setDialog({})} busy={action.isPending} />}
      {query.isPending ? <p role="status">Loading…</p> : query.isError ? <p role="alert">Could not load review cycles.</p> : (
        query.data?.length ? (
          <table className="grid">
            <thead><tr><th>Cycle name</th><th>Year</th><th>Start date</th><th>End date</th><th>Status</th>{admin && <th>Actions</th>}</tr></thead>
            <tbody>
              {query.data.map((cycle) => (
                <tr key={cycle.cycleId}>
                  <td>{cycle.cycleName}</td>
                  <td>{cycle.cycleYear}</td>
                  <td>{formatDate(cycle.startDate)}</td>
                  <td>{formatDate(cycle.endDate)}</td>
                  <td><StatusBadge status={cycle.status} /></td>
                  {admin && (
                    <td>
                      {cycle.status === 'DRAFT' && <button type="button" onClick={() => setDialog({ cycle })}>Edit</button>}
                      {cycle.status === 'DRAFT' && <button type="button" onClick={() => action.mutate({ cycleId: cycle.cycleId, type: 'open' })}>Open cycle</button>}
                      {['OPEN', 'IN_PROGRESS', 'CALIBRATION'].includes(cycle.status) && <button type="button" onClick={() => action.mutate({ cycleId: cycle.cycleId, type: 'close' })}>Close cycle</button>}
                      {['DRAFT', 'OPEN'].includes(cycle.status) && <button type="button" onClick={() => action.mutate({ cycleId: cycle.cycleId, type: 'generate' })}>Generate reviews</button>}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p>No review cycles</p>
      )}
      {dialog && <CycleDialog cycle={dialog.cycle} onClose={() => setDialog(null)} onSaved={() => { setDialog(null); invalidate(); }} />}
    </section>
  );
}
