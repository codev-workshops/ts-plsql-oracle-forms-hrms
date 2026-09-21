import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { ReviewDetail } from './ReviewDetail';
import { StatusBadge } from './StatusBadge';

export function MyReviewsTab() {
  const [selected, setSelected] = useState<number | null>(null);
  const reviews = useQuery({ queryKey: ['performance', 'reviews', 'mine'], queryFn: () => api.performance.listMyReviews() });
  const cycles = useQuery({
    queryKey: ['performance', 'cycles', { status: 'DRAFT,OPEN,IN_PROGRESS,CALIBRATION,CLOSED' }],
    queryFn: () => api.performance.listCycles({ status: 'DRAFT,OPEN,IN_PROGRESS,CALIBRATION,CLOSED' }),
  });
  const names = new Map((cycles.data ?? []).map((cycle) => [cycle.cycleId, cycle.cycleName]));
  return (
    <section aria-labelledby="my-reviews-title">
      <h2 id="my-reviews-title">My Reviews</h2>
      {reviews.isPending ? <p role="status">Loading…</p> : reviews.isError ? <p role="alert">Could not load reviews.</p> : reviews.data?.length ? (
        <>
          <table className="grid">
            <thead><tr><th>Cycle</th><th>Employee</th><th>Reviewer</th><th>Status</th><th>Overall rating</th><th>Rating label</th><th /></tr></thead>
            <tbody>
              {reviews.data.map((review) => (
                <tr key={review.reviewId}>
                  <td>{names.get(review.cycleId) ?? `Cycle #${review.cycleId}`}</td>
                  <td>{review.employeeName}</td>
                  <td>{review.reviewerName}</td>
                  <td><StatusBadge status={review.status} /></td>
                  <td>{review.overallRating ?? '—'}</td>
                  <td>{review.ratingLabel ?? '—'}</td>
                  <td><button type="button" onClick={() => setSelected(review.reviewId)}>Open</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          {selected !== null && <ReviewDetail reviewId={selected} />}
        </>
      ) : <p>No reviews</p>}
    </section>
  );
}
