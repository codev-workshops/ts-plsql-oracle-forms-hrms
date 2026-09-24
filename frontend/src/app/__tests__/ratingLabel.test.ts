import { ratingLabelFor } from '../ratingLabel';

describe('ratingLabelFor', () => {
  it.each([
    [4.5, 'Exceptional'],
    [4.49, 'Exceeds Expectations'],
    [3.5, 'Exceeds Expectations'],
    [2.5, 'Meets Expectations'],
    [1.5, 'Needs Improvement'],
    [1.4, 'Unsatisfactory'],
  ])('maps %s to %s', (rating, expected) => {
    expect(ratingLabelFor(rating)).toBe(expected);
  });
});
