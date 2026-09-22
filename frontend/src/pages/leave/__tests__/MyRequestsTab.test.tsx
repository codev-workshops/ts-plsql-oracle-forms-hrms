import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { server } from '../../../mocks/server';
import { MyRequestsTab } from '../MyRequestsTab';
import { renderLeaveAs } from './leaveTestUtils';

describe('MyRequestsTab', () => {
  it('lists the caller’s requests newest first with a Cancel Request action only on PENDING/APPROVED rows', async () => {
    renderLeaveAs(SEED_ACCOUNTS.staff.email, <MyRequestsTab />);
    const grid = await screen.findByRole('table', { name: 'My leave requests' });
    const rows = within(grid).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(within(rows[0]).getByText('Long weekend')).toBeInTheDocument();
    expect(within(rows[0]).getByText('PENDING')).toBeInTheDocument();
    expect(within(rows[0]).getByRole('button', { name: /Cancel request/ })).toBeInTheDocument();
    expect(within(rows[1]).getByText('APPROVED')).toBeInTheDocument();
    expect(within(rows[1]).getByRole('button', { name: /Cancel request/ })).toBeInTheDocument();
    const cancelled = rows.find((r) => within(r).queryByText('CANCELLED'))!;
    expect(within(cancelled).queryByRole('button')).not.toBeInTheDocument();
    const rejected = rows.find((r) => within(r).queryByText('REJECTED'))!;
    expect(within(rejected).queryByRole('button')).not.toBeInTheDocument();
  });

  it('shows the balance grid with the VAL-05 available column', async () => {
    renderLeaveAs(SEED_ACCOUNTS.staff.email, <MyRequestsTab />);
    const balances = await screen.findByRole('table', { name: 'Leave balances' });
    const pto = within(balances).getByRole('row', { name: /Paid Time Off/ });
    // 5 + 11.25 - 3 + 0 - 2 = 11.25
    expect(within(pto).getByTestId('available-PTO')).toHaveTextContent('11.25');
    expect(within(balances).getByTestId('available-SICK')).toHaveTextContent('6.47');
  });

  it('cancels a pending request through the dialog and refreshes grid + balances', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.staff.email, <MyRequestsTab />);
    await screen.findByTestId('available-PTO');
    await user.click(screen.getByRole('button', { name: 'Cancel request 902' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel leave request' });
    await user.type(within(dialog).getByLabelText('Reason'), 'Plans changed');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm cancel' }));
    await screen.findByText('Leave request cancelled');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const row = await screen.findByRole('row', { name: /Plans changed/ });
    expect(within(row).getByText('CANCELLED')).toBeInTheDocument();
    // pending 2 released: 5 + 11.25 - 3 - 0 = 13.25
    await waitFor(() => expect(screen.getByTestId('available-PTO')).toHaveTextContent('13.25'));
  });

  it('surfaces -20204 from the server as a toast when the transition is invalid', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('/api/leave/requests/:id/cancel', () =>
        HttpResponse.json({ code: '-20204', message: 'Cannot cancel request in status: CANCELLED', traceId: 't' }, { status: 422 }),
      ),
    );
    renderLeaveAs(SEED_ACCOUNTS.staff.email, <MyRequestsTab />);
    await user.click(await screen.findByRole('button', { name: 'Cancel request 902' }));
    await user.click(screen.getByRole('button', { name: 'Confirm cancel' }));
    expect(await screen.findByText('Cannot cancel request in status: CANCELLED')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('renders the empty state', async () => {
    server.use(http.get('/api/leave/requests/mine', () => HttpResponse.json([])));
    renderLeaveAs(SEED_ACCOUNTS.staff.email, <MyRequestsTab />);
    expect(await screen.findByText('No leave requests')).toBeInTheDocument();
  });
});
