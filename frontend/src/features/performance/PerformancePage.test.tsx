import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PerformancePage } from './index';

type Handler = (url: string, init?: RequestInit) => { status: number; body?: unknown };

function mockFetch(handler: Handler) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const { status, body } = handler(String(input), init);
    return new Response(body === undefined ? null : JSON.stringify(body), { status });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

const review = {
  reviewId: 5, cycleId: 3, empId: 22, employeeName: 'THOMAS BAKER', reviewerEmpId: 21,
  reviewerName: 'JENNIFER PARK', status: 'NOT_STARTED',
  goals: [{ goalId: 9, goalTitle: 'Automate close', weightPct: 40, progressPct: 0, status: 'NOT_STARTED' }],
};
const cycle = { cycleId: 3, cycleName: 'FY2027', cycleYear: 2026, startDate: '2026-12-01', endDate: '2027-02-28',
  status: 'DRAFT' };

function body(init?: RequestInit) {
  return init?.body ? JSON.parse(String(init.body)) : undefined;
}

describe('PerformancePage', () => {
  beforeEach(() => window.localStorage.setItem('hrms.token', 't'));
  afterEach(() => vi.unstubAllGlobals());

  it('lists my reviews and submits a self-assessment', async () => {
    let current: Record<string, unknown> = review;
    const fetchMock = mockFetch((url, init) => {
      if (url.endsWith('/reviews?scope=mine')) return { status: 200, body: [current] };
      if (url.endsWith('/self-assessment')) {
        current = { ...review, status: 'MANAGER_REVIEW', selfAssessment: body(init).selfAssessment, selfRating: 4 };
        return { status: 200, body: current };
      }
      if (url.endsWith('/reviews/5')) return { status: 200, body: current };
      return { status: 404, body: { code: '-20990', message: 'nf' } };
    });
    const user = userEvent.setup();
    render(<PerformancePage />);
    expect(await screen.findByText('JENNIFER PARK')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(await screen.findByTestId('review-status')).toHaveTextContent('NOT_STARTED');
    expect(screen.getByText('Automate close')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Self assessment'), 'Closed the books early');
    await user.type(screen.getByLabelText('Self rating (1-5)'), '4');
    await user.click(screen.getByRole('button', { name: 'Submit self-assessment' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Self-assessment submitted');
    expect(await screen.findByTestId('review-status')).toHaveTextContent('MANAGER_REVIEW');
    const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/self-assessment'));
    expect(body(call?.[1])).toEqual({ selfAssessment: 'Closed the books early', selfRating: 4 });
  });

  it('shows the server error when a manager rating is out of range', async () => {
    mockFetch((url) => {
      if (url.endsWith('/reviews?scope=team')) return { status: 200, body: [{ ...review, status: 'MANAGER_REVIEW' }] };
      if (url.endsWith('/manager-review')) {
        return { status: 422, body: { code: '-20403', message: 'Rating must be between 1.0 and 5.0' } };
      }
      if (url.endsWith('/reviews/5')) return { status: 200, body: { ...review, status: 'MANAGER_REVIEW' } };
      return { status: 200, body: [] };
    });
    const user = userEvent.setup();
    render(<PerformancePage />);
    await user.click(screen.getByRole('tab', { name: 'Team reviews' }));
    await user.click(await screen.findByRole('button', { name: 'Open' }));
    await user.type(await screen.findByLabelText('Overall rating (1-5)'), '6');
    await user.click(screen.getByRole('button', { name: 'Submit manager review' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Rating must be between 1.0 and 5.0 (-20403)');
  });

  it('acknowledges a completed review and updates goal progress', async () => {
    const fetchMock = mockFetch((url) => {
      if (url.endsWith('/reviews?scope=mine')) return { status: 200, body: [review] };
      if (url.endsWith('/acknowledge')) return { status: 200, body: { ...review, status: 'ACKNOWLEDGED' } };
      if (url.endsWith('/goals/9/progress')) return { status: 200, body: { ...review.goals[0], progressPct: 60 } };
      if (url.endsWith('/reviews/5')) {
        return { status: 200, body: { ...review, status: 'COMPLETED', overallRating: 3.7,
          ratingLabel: 'Exceeds Expectations' } };
      }
      return { status: 404, body: { code: '-20990', message: 'nf' } };
    });
    const user = userEvent.setup();
    render(<PerformancePage />);
    await user.click(await screen.findByRole('button', { name: 'Open' }));
    expect(await screen.findByText('3.7 (Exceeds Expectations)')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Progress for Automate close'), '60');
    await user.click(screen.getByRole('button', { name: 'Update' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Goal progress updated');
    const progressCall = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/goals/9/progress'));
    expect(body(progressCall?.[1])).toEqual({ progressPct: 60 });
    await user.click(screen.getByRole('button', { name: 'Acknowledge review' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Review acknowledged');
  });

  it('creates, opens and reports on cycles', async () => {
    const fetchMock = mockFetch((url, init) => {
      if (url.endsWith('/performance/cycles') && init?.method === 'POST') {
        return { status: 201, body: { ...cycle, ...body(init) } };
      }
      if (url.endsWith('/performance/cycles')) return { status: 200, body: [cycle] };
      if (url.endsWith('/cycles/3/open')) {
        return { status: 409, body: { code: '-20401', message: 'Cannot open cycle - must be in DRAFT status' } };
      }
      if (url.endsWith('/cycles/3/generate-reviews')) {
        return { status: 200, body: { operation: 'GENERATE_REVIEWS', rowsAffected: 21 } };
      }
      if (url.endsWith('/cycles/3/rating-distribution')) {
        return { status: 200, body: [{ ratingLabel: 'Exceptional', count: 2 }] };
      }
      return { status: 404, body: { code: '-20990', message: 'nf' } };
    });
    const user = userEvent.setup();
    render(<PerformancePage />);
    await user.click(screen.getByRole('tab', { name: 'Review cycles' }));
    expect(await screen.findByText('FY2027')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Generate reviews' }));
    expect(await screen.findByRole('status')).toHaveTextContent('21 review(s) generated');
    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('(-20401)');
    await user.click(screen.getByRole('button', { name: 'Ratings' }));
    const dist = await screen.findByRole('table', { name: 'Rating distribution' });
    expect(within(dist).getByText('Exceptional')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Cycle name'), 'FY2028');
    await user.type(screen.getByLabelText('Start date'), '2027-12-01');
    await user.type(screen.getByLabelText('End date'), '2028-02-28');
    await user.click(screen.getByRole('button', { name: 'Create cycle' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Cycle #3 created (DRAFT)');
    const create = fetchMock.mock.calls.find(([u, i]) => String(u).endsWith('/performance/cycles') && i?.method === 'POST');
    expect(body(create?.[1])).toEqual({ cycleName: 'FY2028', startDate: '2027-12-01', endDate: '2028-02-28' });
  });
});
