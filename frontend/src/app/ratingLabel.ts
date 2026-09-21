import type { RatingLabel } from '../api/types';

export function ratingLabelFor(rating: number): RatingLabel {
  if (rating >= 4.5) return 'Exceptional';
  if (rating >= 3.5) return 'Exceeds Expectations';
  if (rating >= 2.5) return 'Meets Expectations';
  if (rating >= 1.5) return 'Needs Improvement';
  return 'Unsatisfactory';
}
