import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LeavePage } from './index';

type Handler = (url: string, init?: RequestInit) => { status: number; body?: unknown };

function mockFetch(handler: Handler) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const { status, body } = handler(String(input), init);
    return new Response(body === undefined ? null : JSON.stringify(body), { status });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

const types = [
  { leaveTypeId: 1, code: 'PTO', name: 'Paid Time Off', accrual: true, requiresApproval: true, minTenureDays: 0 },
];
const balances = [
  { leaveTypeId: 1, leaveTypeCode: 'PTO', leaveTypeName: 'Paid Time Off', year: 2026, openingBalance: 0,
    accrued: 12.5, used: 0, adjustment: 0, pending: 0, available: 12.5 },
];
const pendingRequest = {
  requestId: 7, empId: 22, employeeName: 'THOMAS BAKER', leaveTypeId: 1, leaveTypeName: 'Paid Time Off',
  startDate: '2026-12-07', endDate: '2026-12-11', totalDays: 5, halfDay: false, status: 'PENDING',
};

describe('LeavePage', () => {
  beforeEach(() => window.localStorage.setItem('hrms.token', 't'));
  afterEach(() => vi.unstubAllGlobals());

  it('shows balances and my requests', async () => {
    mockFetch((url) => {
      if (url.includes('/balances')) return { status: 200, body: balances };
      if (url.endsWith('/leave/requests')) return { status: 200, body: [pendingRequest] };
      return { status: 404, body: { code: '-20002', message: 'nf' } };
    });
    render(<LeavePage />);
    expect(await screen.findByText('12.5', { selector: 'td:last-child' })).toBeInTheDocument();
    expect(await screen.findByText('PENDING')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('submits a request and shows the server status', async () => {
    const fetchMock = mockFetch((url, init) => {
      if (url.endsWith('/leave/types')) return { status: 200, body: types };
      if (url.endsWith('/leave/requests') && init?.method === 'POST') return { status: 201, body: pendingRequest };
      if (url.includes('/balances')) return { status: 200, body: balances };
      return { status: 200, body: [] };
    });
    const user = userEvent.setup();
    render(<LeavePage />);
    await user.click(screen.getByRole('tab', { name: 'Request leave' }));
    await screen.findByRole('option', { name: 'Paid Time Off' });
    await user.selectOptions(screen.getByLabelText('Leave type'), '1');
    await user.type(screen.getByLabelText('Start date'), '2026-12-07');
    await user.type(screen.getByLabelText('End date'), '2026-12-11');
    await user.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Request #7 submitted (PENDING, 5 day(s))');
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({ leaveTypeId: 1, startDate: '2026-12-07',
      endDate: '2026-12-11', halfDay: false });
  });

  it('blocks end before start client-side and shows server error codes', async () => {
    mockFetch((url, init) => {
      if (url.endsWith('/leave/types')) return { status: 200, body: types };
      if (init?.method === 'POST')
        return { status: 422, body: { code: '-20201', message: 'Insufficient leave balance' } };
      return { status: 200, body: [] };
    });
    const user = userEvent.setup();
    render(<LeavePage />);
    await user.click(screen.getByRole('tab', { name: 'Request leave' }));
    await screen.findByRole('option', { name: 'Paid Time Off' });
    await user.selectOptions(screen.getByLabelText('Leave type'), '1');
    await user.type(screen.getByLabelText('Start date'), '2026-12-10');
    await user.type(screen.getByLabelText('End date'), '2026-12-07');
    await user.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Start date must be before or equal to end date');
    await user.clear(screen.getByLabelText('End date'));
    await user.type(screen.getByLabelText('End date'), '2026-12-31');
    await user.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Insufficient leave balance (-20201)');
  });

  it('lets a manager approve a pending request', async () => {
    let approved = false;
    const fetchMock = mockFetch((url, init) => {
      if (url.endsWith('/approve') && init?.method === 'POST') {
        approved = true;
        return { status: 200, body: { ...pendingRequest, status: 'APPROVED' } };
      }
      if (url.endsWith('/leave/approvals')) return { status: 200, body: approved ? [] : [pendingRequest] };
      return { status: 200, body: [] };
    });
    const user = userEvent.setup();
    render(<LeavePage />);
    await user.click(screen.getByRole('tab', { name: 'Approvals' }));
    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(screen.getByText('No requests waiting for your approval.')).toBeInTheDocument());
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/leave/requests/7/approve'))).toBe(true);
  });
});
