import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { useAuth } from '../../app/AuthContext';
import { formatDate } from '../../app/format';
import { GoalDialog } from './GoalDialog';
import { GoalProgressDialog } from './GoalProgressDialog';
import { StatusBadge } from './StatusBadge';
import type { PerformanceGoal, PerformanceReview } from '../../api/types';

export function GoalsGrid({ review }: { review: PerformanceReview }) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<'add' | PerformanceGoal | null>(null);
  const query = useQuery({ queryKey: ['performance', 'goals', review.reviewId], queryFn: () => api.performance.listGoals(review.reviewId) });
  const canEdit = (review.empId === user?.empId || review.reviewerEmpId === user?.empId) && !['COMPLETED', 'ACKNOWLEDGED'].includes(review.status);
  const canProgress = (review.empId === user?.empId || review.reviewerEmpId === user?.empId) && review.status !== 'ACKNOWLEDGED';
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['performance', 'goals', review.reviewId] });
  return (
    <section aria-labelledby="goals-grid-title">
      <h3 id="goals-grid-title">Goals</h3>
      {canEdit && <button type="button" onClick={() => setDialog('add')}>Add goal</button>}
      {query.isPending ? <p role="status">Loading goals…</p> : query.isError ? <p role="alert">Could not load goals.</p> : query.data?.length ? (
        <table className="grid">
          <thead><tr><th>Title</th><th>Category</th><th>Weight %</th><th>Progress %</th><th>Status</th><th>Target date</th><th /></tr></thead>
          <tbody>{query.data.map((goal) => <tr key={goal.goalId}><td>{goal.goalTitle}</td><td>{goal.goalCategory}</td><td>{goal.weightPct}</td><td>{goal.progressPct}</td><td><StatusBadge status={goal.status} /></td><td>{formatDate(goal.targetDate)}</td><td>{canProgress && <button type="button" onClick={() => setDialog(goal)}>Progress</button>}</td></tr>)}</tbody>
        </table>
      ) : <p>No goals</p>}
      {dialog === 'add' && <GoalDialog review={review} onClose={() => setDialog(null)} onSaved={() => { setDialog(null); refresh(); }} />}
      {dialog && dialog !== 'add' && <GoalProgressDialog goal={dialog} onClose={() => setDialog(null)} onSaved={() => { setDialog(null); refresh(); }} />}
    </section>
  );
}
