import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { server } from '../../../mocks/server';
import { getDto } from '../../../validation/schema';
import { ApprovalsTab } from '../ApprovalsTab';
import { renderLeaveAs } from './leaveTestUtils';

describe('ApprovalsTab', () => {
  it('lists requests pending for the caller as designated approver, oldest first', async () => {
    renderLeaveAs(SEED_ACCOUNTS.manager.email, <ApprovalsTab />);
    const grid = await screen.findByRole('table', { name: 'Pending leave approvals' });
    const rows = within(grid).getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getAllByRole('cell')[0].textContent)).toEqual(['DAVID MARTINEZ', 'EMILY JOHNSON']);
    expect(within(rows[1]).getByText('EMP-000012')).toBeInTheDocument();
  });

  it('shows the empty state for a caller with no queue', async () => {
    renderLeaveAs(SEED_ACCOUNTS.staff.email, <ApprovalsTab />);
    expect(await screen.findByText('No pending approvals')).toBeInTheDocument();
  });

  it('approves with optional comments and removes the row', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.manager.email, <ApprovalsTab />);
    await user.click(await screen.findByRole('button', { name: 'Approve request 904' }));
    const dialog = screen.getByRole('dialog', { name: 'Approve leave request' });
    expect(within(dialog).getByText(/EMILY JOHNSON – Paid Time Off/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    await screen.findByText('Leave request approved');
    await waitFor(() => expect(screen.queryByRole('row', { name: /EMILY JOHNSON/ })).not.toBeInTheDocument());
  });

  it('reject requires comments (generated LeaveRejectRequest rule) then removes the row', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.manager.email, <ApprovalsTab />);
    await user.click(await screen.findByRole('button', { name: 'Reject request 902' }));
    const dialog = screen.getByRole('dialog', { name: 'Reject leave request' });
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(getDto('LeaveRejectRequest').fields.comments.messages.required!);
    await user.type(within(dialog).getByLabelText(/Comments/), 'Coverage gap');
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }));
    await screen.findByText('Leave request rejected');
    await waitFor(() => expect(screen.queryByRole('row', { name: /DAVID MARTINEZ/ })).not.toBeInTheDocument());
  });

  it('toasts -20204 when the request is no longer pending', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('/api/leave/requests/:id/approve', () =>
        HttpResponse.json({ code: '-20204', message: 'Cannot approve request in status: CANCELLED', traceId: 't' }, { status: 422 }),
      ),
    );
    renderLeaveAs(SEED_ACCOUNTS.manager.email, <ApprovalsTab />);
    await user.click(await screen.findByRole('button', { name: 'Approve request 904' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('Cannot approve request in status: CANCELLED')).toBeInTheDocument();
  });
});
