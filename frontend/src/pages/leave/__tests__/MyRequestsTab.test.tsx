import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { MyRequestsTab } from '../MyRequestsTab';
import { renderAs } from './helpers';

describe('MyRequestsTab', () => {
  it('shows the JWT employee balances (available = opening + accrued - used + adjustment - pending) and requests', async () => {
    renderAs(SEED_ACCOUNTS.staff.email, <MyRequestsTab />);
    const balances = await screen.findByRole('table', { name: 'Leave balances' });
    const pto = within(balances).getByRole('row', { name: /Paid Time Off/ });
    // seed: opening 5, accrued 10, used 2, adjustment 0, pending 3
    expect(within(pto).getByTestId('available-PTO')).toHaveTextContent('10');
    const requests = await screen.findByRole('table', { name: 'My leave requests' });
    const rows = within(requests).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(within(requests).getByText('PENDING')).toBeInTheDocument();
    expect(within(requests).getByText('REJECTED')).toBeInTheDocument();
    expect(within(requests).getByText('½ (PM)')).toBeInTheDocument();
  });

  it('only allows Cancel Request on PENDING/APPROVED rows and refreshes grid + balances after cancelling', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <MyRequestsTab />);
    await screen.findByRole('table', { name: 'My leave requests' });
    expect(screen.getByRole('button', { name: 'Cancel request 3001' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Cancel request 3002' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Cancel request 3003' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel request 3004' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Cancel request 3001' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel leave request' });
    await user.type(within(dialog).getByLabelText('Reason (optional)'), 'Plans changed');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm cancellation' }));
    await screen.findByText('Leave request cancelled');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cancel request 3001' })).toBeDisabled());
    expect(screen.getAllByText('CANCELLED')).toHaveLength(2);
    // pending 3 released → available 13
    await waitFor(() => expect(screen.getByTestId('available-PTO')).toHaveTextContent('13'));
  });

  it('closes the dialog without cancelling when the user keeps the request', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <MyRequestsTab />);
    await user.click(await screen.findByRole('button', { name: 'Cancel request 3002' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Keep request' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel request 3002' })).toBeEnabled();
  });
});
