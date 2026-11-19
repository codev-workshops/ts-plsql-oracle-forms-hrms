import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../../api/client';
import { ReportingPage } from './index';

const auth = vi.hoisted(() => ({ user: { gradeLevel: 5 }, logout: vi.fn() }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => auth }));

function mockFetch(body: unknown, status = 200) {
  const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('ReportingPage', () => {
  afterEach(() => {
    tokenStore.clear();
    auth.user.gradeLevel = 5;
    vi.unstubAllGlobals();
  });

  it('sends the numeric headcount filters and renders the results in column order', async () => {
    tokenStore.set('manager-test');
    const fetchMock = mockFetch({
      name: 'Headcount',
      generatedAt: '2026-11-19T09:00:00',
      columns: ['headcount', 'location_name'],
      rows: [{ location_name: 'Corporate Headquarters', headcount: 12 }],
    });
    const user = userEvent.setup();
    render(<ReportingPage />);

    await user.type(screen.getByLabelText('As of'), '2026-11-19');
    await user.type(screen.getByLabelText('Department ID'), '20');
    await user.type(screen.getByLabelText('Location ID'), '2');
    await user.click(screen.getByRole('button', { name: 'Run report' }));
    const table = await screen.findByRole('table', { name: 'Headcount' });
    expect(within(table).getAllByRole('columnheader').map((th) => th.textContent))
      .toEqual(['Headcount', 'Location name']);
    expect(within(table).getByText('Corporate Headquarters')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/reports/headcount?asOf=2026-11-19&deptId=20&locationId=2',
      expect.objectContaining({ headers: { Accept: 'application/json', Authorization: 'Bearer manager-test' } }));
    expect(screen.getByRole('button', { name: 'Download CSV' })).toBeEnabled();

    await user.selectOptions(screen.getByLabelText('Report type'), 'eeo');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Location ID')).not.toBeInTheDocument();
  });

  it('checks reversed date ranges without sending a request', async () => {
    const fetchMock = mockFetch({});
    const user = userEvent.setup();
    render(<ReportingPage />);
    await user.selectOptions(screen.getByLabelText('Report type'), 'turnover');
    await user.type(screen.getByLabelText('Start date'), '2026-11-19');
    await user.type(screen.getByLabelText('End date'), '2026-11-18');
    await user.click(screen.getByRole('button', { name: 'Run report' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Start date must be before or equal to end date');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows an empty result with a downloadable header', async () => {
    const fetchMock = mockFetch({
      name: 'Payroll summary', generatedAt: '2026-11-19T09:00:00', columns: ['dept_name'], rows: [],
    });
    const user = userEvent.setup();
    render(<ReportingPage />);
    await user.selectOptions(screen.getByLabelText('Report type'), 'payroll-summary');
    await user.type(screen.getByLabelText('Pay period ID'), '501');
    await user.click(screen.getByRole('button', { name: 'Run report' }));

    expect(await screen.findByText('No rows match these filters.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download CSV' })).toBeEnabled();
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/reports/payroll-summary?periodId=501', expect.anything());
  });

  it('shows the API error without keeping a downloadable result', async () => {
    mockFetch({ code: '-20000', message: 'Malformed request' }, 400);
    const user = userEvent.setup();
    render(<ReportingPage />);
    await user.click(screen.getByRole('button', { name: 'Run report' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Malformed request (-20000)');
    expect(screen.queryByRole('button', { name: 'Download CSV' })).not.toBeInTheDocument();
  });

  it('does not query reports for unauthorized employees', () => {
    auth.user.gradeLevel = 4;
    const fetchMock = mockFetch({});
    render(<ReportingPage />);
    expect(screen.getByRole('alert')).toHaveTextContent('You do not have permission to view reports.');
    expect(screen.queryByRole('button', { name: 'Run report' })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
