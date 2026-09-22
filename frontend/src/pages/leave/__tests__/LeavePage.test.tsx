import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { LeavePage } from '../LeavePage';
import { renderAs } from './helpers';

describe('LeavePage', () => {
  it('renders the four HRMS_LEAVE tabs and switches between them', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <LeavePage />);
    expect(screen.getByRole('heading', { name: 'Leave' })).toBeInTheDocument();
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent);
    expect(tabs).toEqual(['My Requests', 'Submit Request', 'Approvals', 'Team Calendar']);
    expect(screen.getByRole('tab', { name: 'My Requests' })).toHaveAttribute('aria-selected', 'true');
    await screen.findByRole('table', { name: 'My leave requests' });

    await user.click(screen.getByRole('tab', { name: 'Submit Request' }));
    expect(await screen.findByRole('form', { name: 'Submit leave request' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Approvals' }));
    await screen.findByRole('table', { name: 'Pending leave approvals' });
    await user.click(screen.getByRole('tab', { name: 'Team Calendar' }));
    await screen.findByRole('table', { name: 'Team leave calendar' });
    expect(screen.getByRole('tab', { name: 'Team Calendar' })).toHaveAttribute('aria-selected', 'true');
  });
});
