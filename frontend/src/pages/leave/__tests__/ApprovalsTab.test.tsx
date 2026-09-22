import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { getDto } from '../../../validation/schema';
import { ApprovalsTab } from '../ApprovalsTab';
import { renderAs } from './helpers';

describe('ApprovalsTab', () => {
  it('lists only requests where the JWT user is the approver', async () => {
    renderAs(SEED_ACCOUNTS.manager.email, <ApprovalsTab />, '/leave/approvals');
    const table = await screen.findByRole('table', { name: 'Pending leave approvals' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(table).getByRole('row', { name: /DAVID MARTINEZ/ })).toBeInTheDocument();
    expect(within(table).getByRole('row', { name: /EMILY JOHNSON/ })).toBeInTheDocument();
  });

  it('shows an empty state for a staff member with no reports', async () => {
    renderAs(SEED_ACCOUNTS.staff.email, <ApprovalsTab />, '/leave/approvals');
    expect(await screen.findByText('Nothing waiting for your approval.')).toBeInTheDocument();
  });

  it('approves a request and removes it from the grid', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.manager.email, <ApprovalsTab />, '/leave/approvals');
    const row = await screen.findByRole('row', { name: /DAVID MARTINEZ/ });
    await user.click(within(row).getByRole('button', { name: 'Approve' }));
    await screen.findByText('Approved leave for DAVID MARTINEZ');
    await waitFor(() => expect(screen.queryByRole('row', { name: /DAVID MARTINEZ/ })).not.toBeInTheDocument());
    expect(screen.getByRole('row', { name: /EMILY JOHNSON/ })).toBeInTheDocument();
  });

  it('requires a rejection reason (validation-schema LeaveRejectRequest) and rejects', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.manager.email, <ApprovalsTab />, '/leave/approvals');
    const row = await screen.findByRole('row', { name: /EMILY JOHNSON/ });
    await user.click(within(row).getByRole('button', { name: 'Reject' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reject leave request' });
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }));
    expect(await screen.findByText(getDto('LeaveRejectRequest').fields.comments.messages.required!)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Rejection reason *'), 'Coverage needed that day');
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }));
    await screen.findByText('Rejected leave for EMILY JOHNSON');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.queryByRole('row', { name: /EMILY JOHNSON/ })).not.toBeInTheDocument());
  });
});
