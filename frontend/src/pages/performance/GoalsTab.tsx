import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { GoalsGrid } from './GoalsGrid';

export function GoalsTab() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const reviewId = searchParams.get('reviewId');
  const mine = useQuery({ queryKey: ['performance', 'reviews', 'mine'], queryFn: () => api.performance.listMyReviews(), enabled: !reviewId });
  const direct = useQuery({ queryKey: ['performance', 'reviews', Number(reviewId)], queryFn: () => api.performance.getReview(Number(reviewId)), enabled: Boolean(reviewId) });
  const reviews = mine.data ?? [];
  const selectedId = reviewId ? Number(reviewId) : reviews[0]?.reviewId;
  const selected = direct.data ?? reviews.find((review) => review.reviewId === selectedId);
  return (
    <section aria-labelledby="goals-title">
      <h2 id="goals-title">Goals</h2>
      {!reviewId && (
        <div className="field">
          <label htmlFor="goal-review">Review</label>
          <select id="goal-review" value={selectedId ?? ''} onChange={(event) => navigate(`/performance/goals?reviewId=${event.target.value}`)}>
            {reviews.map((review) => <option key={review.reviewId} value={review.reviewId}>{review.employeeName} — {review.cycleId}</option>)}
          </select>
        </div>
      )}
      {selected ? <GoalsGrid review={selected} /> : <p>{mine.isPending || direct.isPending ? 'Loading…' : 'No reviews'}</p>}
    </section>
  );
}
