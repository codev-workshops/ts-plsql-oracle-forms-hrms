import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { server } from '../../../mocks/server';
import { renderReportsAs } from './reportsTestUtils';

const manager = SEED_ACCOUNTS.manager.email; // REPORTS:VIEW + PAYROLL:VIEW
const staff = SEED_ACCOUNTS.staff.email; // neither

function stubDownload() {
  const created: string[] = [];
  const objectUrl = vi.fn(() => 'blob:csv');
  const revoke = vi.fn();
  Object.defineProperty(URL, 'createObjectURL', { value: objectUrl, configurable: true });
  Object.defineProperty(URL, 'revokeObjectURL', { value: revoke, configurable: true });
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    created.push(this.download);
  });
  return { created, objectUrl, click };
}

describe('ReportsPage – gating and tabs', () => {
  it('redirects home unless the proxy reports reporting=NEW', async () => {
    renderReportsAs(manager, '/reports', 'LEGACY');
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
  });

  it('redirects home when the user has neither REPORTS:VIEW nor a pending-approvals entitlement', async () => {
    renderReportsAs(staff, '/reports');
    // staff still gets Pending Approvals in `mine=true` mode
    expect(await screen.findByRole('heading', { name: 'Reports' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Pending Approvals']);
  });

  it('shows all six reports for REPORTS:VIEW + PAYROLL:VIEW', async () => {
    renderReportsAs(manager);
    await screen.findByRole('heading', { name: 'Reports' });
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Employee Directory', 'Org Hierarchy', 'Compensation', 'Leave Summary', 'Latest Payroll', 'Pending Approvals']);
  });
});

describe('Employee directory', () => {
  it('loads a paged grid, filters by department and exports CSV via the .csv twin', async () => {
    const user = userEvent.setup();
    const dl = stubDownload();
    let csvAccept: string | null = null;
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('employee-directory.csv')) csvAccept = request.headers.get('accept');
    });
    renderReportsAs(manager, '/reports/employee-directory');
    expect(await screen.findByRole('status')).toHaveTextContent(/Loading employee directory/);
    const table = await screen.findByRole('table', { name: 'Employee Directory' });
    expect(within(table).getAllByRole('row')).toHaveLength(6);
    expect(table).toHaveTextContent('JAMES RICHARDSON');

    const dept = await screen.findByLabelText('Department');
    await waitFor(() => expect(within(dept).getAllByRole('option').length).toBeGreaterThan(1));
    await user.selectOptions(dept, '3');
    await user.click(screen.getByRole('button', { name: 'Run report' }));
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Employee Directory' })).getAllByRole('row')).toHaveLength(4));

    await user.click(screen.getByRole('button', { name: 'Export CSV' }));
    // filename comes from the mock's Content-Disposition, not the client fallback
    await waitFor(() => expect(dl.created).toEqual(['employee-directory-2024-06-30.csv']));
    expect(csvAccept).toBe('text/csv');
    dl.click.mockRestore();
  });

  it('renders the empty state and the server ApiError', async () => {
    renderReportsAs(manager, '/reports/employee-directory');
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Employee Directory' });
    await user.selectOptions(await screen.findByLabelText('Location'), 'SF');
    await user.selectOptions(screen.getByLabelText('Department'), '2');
    await user.click(screen.getByRole('button', { name: 'Run report' }));
    expect(await screen.findByText('No rows match the selected filters.')).toBeInTheDocument();

    server.use(http.get('*/api/reports/employee-directory', () => HttpResponse.json({ code: 'INTERNAL_ERROR', message: 'boom', traceId: 't1' }, { status: 500 })));
    await user.selectOptions(screen.getByLabelText('Department'), '');
    await user.click(screen.getByRole('button', { name: 'Run report' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('INTERNAL_ERROR: boom');
  });
});

describe('Organization hierarchy', () => {
  it('preserves the recursive orgPath and the level indentation', async () => {
    renderReportsAs(manager, '/reports/org-hierarchy');
    const table = await screen.findByRole('table', { name: 'Organization Hierarchy' });
    expect(table).toHaveTextContent('JAMES RICHARDSON > JENNIFER PARK > THOMAS BAKER');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Root employee id'), '21');
    await user.click(screen.getByRole('button', { name: 'Run report' }));
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Organization Hierarchy' })).getAllByRole('row')).toHaveLength(4));
  });
});

describe('Employee compensation', () => {
  it('requires PAYROLL:VIEW in addition to REPORTS:VIEW and shows compa-ratio', async () => {
    renderReportsAs(manager, '/reports/employee-compensation');
    const table = await screen.findByRole('table', { name: 'Employee Compensation' });
    expect(table).toHaveTextContent('1.0417');
  });
});

describe('Leave summary', () => {
  it('shows available (pending deducted) next to legacyAvailable for reconciliation', async () => {
    renderReportsAs(manager, '/reports/leave-summary');
    const table = await screen.findByRole('table', { name: 'Leave Summary' });
    const row = within(table).getAllByRole('row')[1];
    expect(row).toHaveTextContent('DAVID MARTINEZ');
    expect(row).toHaveTextContent('10.00');
    expect(row).toHaveTextContent('12.00');
  });
});

describe('Latest payroll', () => {
  it('lists the latest approved run per period', async () => {
    renderReportsAs(manager, '/reports/payroll-latest');
    const table = await screen.findByRole('table', { name: 'Latest Payroll' });
    expect(within(table).getAllByRole('row').length).toBeGreaterThan(1);
  });
});

describe('Pending approvals', () => {
  it('sends mine=true without any actor empId for users lacking REPORTS:VIEW', async () => {
    const urls: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('/api/reports/pending-approvals')) urls.push(request.url);
    });
    renderReportsAs(staff, '/reports/pending-approvals');
    await waitFor(() => expect(urls.length).toBeGreaterThan(0));
    const url = new URL(urls[0]);
    expect(url.searchParams.get('mine')).toBe('true');
    expect([...url.searchParams.keys()].some((k) => /emp/i.test(k))).toBe(false);
    await waitFor(() => expect(screen.queryByText(/Loading/)).not.toBeInTheDocument());
  });

  it('lets REPORTS:VIEW users filter by item type', async () => {
    renderReportsAs(manager, '/reports/pending-approvals');
    await screen.findByRole('table', { name: 'Pending Approvals' });
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText('Item type'), 'LEAVE');
    await user.click(screen.getByRole('button', { name: 'Run report' }));
    await waitFor(() => {
      const table = screen.getByRole('table', { name: 'Pending Approvals' });
      expect(within(table).getAllByRole('row').slice(1).every((r) => r.textContent?.includes('Leave'))).toBe(true);
    });
  });
});
