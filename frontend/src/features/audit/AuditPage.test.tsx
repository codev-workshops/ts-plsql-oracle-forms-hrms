import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuditPage } from './index';

type Handler = (url: string, init?: RequestInit) => { status: number; body?: unknown };

function mockFetch(handler: Handler) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const { status, body } = handler(String(input), init);
    return new Response(body === undefined ? null : JSON.stringify(body), { status });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

const update = {
  auditId: 11, tableName: 'LEAVE_REQUESTS', recordId: 7, action: 'UPDATE', changedBy: 'jennifer.park@company.com',
  changedDate: '2026-11-18T10:15:00', oldValues: { status: 'PENDING' }, newValues: { status: 'APPROVED' },
};
const insert = {
  auditId: 10, tableName: 'LEAVE_REQUESTS', recordId: 7, action: 'INSERT', changedBy: 'thomas.baker@company.com',
  changedDate: '2026-11-17T09:00:00', newValues: { status: 'PENDING' },
};

describe('AuditPage', () => {
  beforeEach(() => window.localStorage.setItem('hrms.token', 't'));
  afterEach(() => vi.unstubAllGlobals());

  it('searches with filters and shows entry details', async () => {
    const fetchMock = mockFetch((url) => {
      if (url.includes('/audit/entries?')) return { status: 200, body: [update, insert] };
      if (url.endsWith('/audit/entries/11')) return { status: 200, body: update };
      return { status: 404, body: { code: '-20990', message: 'nf' } };
    });
    const user = userEvent.setup();
    render(<AuditPage />);
    await user.type(screen.getByLabelText('Table'), 'leave_requests');
    await user.selectOptions(screen.getByLabelText('Action'), 'UPDATE');
    await user.type(screen.getByLabelText('From'), '2026-11-01');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    const table = await screen.findByRole('table', { name: 'Audit entries' });
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(screen.getByText('2 entries (newest first)')).toBeInTheDocument();
    const url = String(fetchMock.mock.calls[0][0]);
    expect(url).toContain('tableName=leave_requests');
    expect(url).toContain('action=UPDATE');
    expect(url).toContain('from=2026-11-01');
    expect(url).toContain('limit=100');
    expect(url).not.toContain('changedBy');

    await user.click(within(table).getAllByRole('button', { name: 'Details' })[0]);
    expect(await screen.findByLabelText('New values')).toHaveTextContent('"status": "APPROVED"');
    expect(screen.getByLabelText('Old values')).toHaveTextContent('"status": "PENDING"');
  });

  it('rejects a reversed date range without calling the API', async () => {
    const fetchMock = mockFetch(() => ({ status: 200, body: [] }));
    const user = userEvent.setup();
    render(<AuditPage />);
    await user.type(screen.getByLabelText('From'), '2026-11-19');
    await user.type(screen.getByLabelText('To'), '2026-11-01');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('From date must be on or before To date');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows the server error for non-admins', async () => {
    mockFetch(() => ({ status: 403, body: { code: '-20304', message: 'Insufficient privilege' } }));
    const user = userEvent.setup();
    render(<AuditPage />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Insufficient privilege (-20304)');
  });

  it('shows a record trail oldest first', async () => {
    const fetchMock = mockFetch((url) =>
      url.endsWith('/audit/records/LEAVE_REQUESTS/7') ? { status: 200, body: [insert, update] } : { status: 200, body: [] },
    );
    const user = userEvent.setup();
    render(<AuditPage />);
    await user.click(screen.getByRole('tab', { name: 'Record trail' }));
    await user.type(screen.getByLabelText('Table name'), 'LEAVE_REQUESTS');
    await user.type(screen.getByLabelText('Record number'), '7');
    await user.click(screen.getByRole('button', { name: 'Show trail' }));
    const rows = within(await screen.findByRole('table', { name: 'Record trail' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('INSERT');
    expect(rows[2]).toHaveTextContent('UPDATE');
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/audit/records/LEAVE_REQUESTS/7');
  });

  it('purges after confirmation and validates the minimum', async () => {
    const fetchMock = mockFetch((url, init) =>
      url.endsWith('/audit/purge') && init?.method === 'POST'
        ? { status: 200, body: { operation: 'PURGE_AUDIT_LOG', rowsAffected: 3 } }
        : { status: 404 },
    );
    vi.stubGlobal('confirm', vi.fn(() => true));
    const user = userEvent.setup();
    render(<AuditPage />);
    await user.click(screen.getByRole('tab', { name: 'Purge' }));
    const days = screen.getByLabelText('Retention days (blank = system default)');
    await user.type(days, '10');
    await user.click(screen.getByRole('button', { name: 'Purge old entries' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Retention must be at least 30 days');
    expect(fetchMock).not.toHaveBeenCalled();

    await user.clear(days);
    await user.type(days, '365');
    await user.click(screen.getByRole('button', { name: 'Purge old entries' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Purged 3 audit entries');
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ retentionDays: 365 });
  });
});
