import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { getDto } from '../../../validation/schema';
import { TeamCalendarTab } from '../TeamCalendarTab';
import { iso, renderLeaveAs } from './leaveTestUtils';

describe('TeamCalendarTab', () => {
  it('shows approved leave of direct reports for the default 30-day range', async () => {
    renderLeaveAs(SEED_ACCOUNTS.manager.email, <TeamCalendarTab />);
    expect(screen.getByLabelText('From')).toHaveValue(iso(0));
    expect(screen.getByLabelText('To')).toHaveValue(iso(30));
    const grid = await screen.findByRole('table', { name: 'Team leave calendar' });
    const row = within(grid).getByRole('row', { name: /SARAH LEE/ });
    expect(within(row).getByText('APPROVED')).toBeInTheDocument();
    // PENDING (904) and the caller's own rows are excluded
    expect(within(grid).queryByRole('row', { name: /EMILY JOHNSON/ })).not.toBeInTheDocument();
  });

  it('re-queries when the range is applied and shows the empty state', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.manager.email, <TeamCalendarTab />);
    await screen.findByRole('table', { name: 'Team leave calendar' });
    await user.clear(screen.getByLabelText('From'));
    await user.type(screen.getByLabelText('From'), iso(200));
    await user.clear(screen.getByLabelText('To'));
    await user.type(screen.getByLabelText('To'), iso(230));
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('No approved leave in this range')).toBeInTheDocument();
  });

  it('blocks from > to with the generated -20210 message before calling the server', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.manager.email, <TeamCalendarTab />);
    await screen.findByRole('table', { name: 'Team leave calendar' });
    await user.clear(screen.getByLabelText('To'));
    await user.type(screen.getByLabelText('To'), iso(-3));
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(getDto('BusinessDaysQuery').fields.end.rules![0].message);
    await waitFor(() => expect(screen.getByRole('table', { name: 'Team leave calendar' })).toBeInTheDocument());
  });

  it('an employee without reports sees the empty state', async () => {
    renderLeaveAs(SEED_ACCOUNTS.staff.email, <TeamCalendarTab />);
    expect(await screen.findByText('No approved leave in this range')).toBeInTheDocument();
  });
});
