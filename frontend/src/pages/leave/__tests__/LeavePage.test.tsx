import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { LeavePage } from '../LeavePage';
import { renderLeaveAs } from './leaveTestUtils';

const page = (
  <Routes>
    <Route path="/leave/*" element={<LeavePage />} />
  </Routes>
);

describe('LeavePage', () => {
  it('renders the four HRMS_LEAVE tabs and routes between them', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.staff.email, page);
    expect(screen.getByRole('heading', { name: 'Leave' })).toBeInTheDocument();
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent);
    expect(tabs).toEqual(['My Requests', 'Submit Request', 'Approvals', 'Team Calendar']);
    expect(screen.getByRole('tab', { name: 'My Requests' })).toHaveAttribute('aria-selected', 'true');
    await screen.findByRole('heading', { name: 'My Requests' });

    await user.click(screen.getByRole('tab', { name: 'Submit Request' }));
    expect(await screen.findByRole('heading', { name: 'Submit Request' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Approvals' }));
    expect(await screen.findByRole('heading', { name: 'Approvals' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Team Calendar' }));
    expect(await screen.findByRole('heading', { name: 'Team Calendar' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Team Calendar' })).toHaveAttribute('aria-selected', 'true');
  });

  it('opens directly on a deep-linked tab', async () => {
    renderLeaveAs(SEED_ACCOUNTS.manager.email, page, '/leave/approvals');
    expect(await screen.findByRole('heading', { name: 'Approvals' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Approvals' })).toHaveAttribute('aria-selected', 'true');
  });
});
