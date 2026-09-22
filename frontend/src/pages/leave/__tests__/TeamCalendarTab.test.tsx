import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { mondayPlusWeeks } from '../../../mocks/leaveStore';
import { TeamCalendarTab } from '../TeamCalendarTab';
import { renderAs } from './helpers';

describe('TeamCalendarTab', () => {
  it('shows approved/taken leave of the JWT manager reports in the default 30-day window', async () => {
    renderAs(SEED_ACCOUNTS.manager.email, <TeamCalendarTab />, '/leave/team-calendar');
    const table = await screen.findByRole('table', { name: 'Team leave calendar' });
    const rows = within(table).getAllByRole('row').slice(1);
    // seed: 3002 (DAVID, w4 APPROVED) and 3006 (SARAH, w2 APPROVED); PENDING rows are excluded
    expect(rows).toHaveLength(2);
    expect(within(table).getByRole('row', { name: /SARAH LEE/ })).toBeInTheDocument();
    expect(within(table).getByRole('row', { name: /DAVID MARTINEZ/ })).toBeInTheDocument();
    expect(within(table).getAllByText('APPROVED')).toHaveLength(2);
  });

  it('re-queries when the range changes and validates the range', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.manager.email, <TeamCalendarTab />, '/leave/team-calendar');
    await screen.findByRole('table', { name: 'Team leave calendar' });
    const from = screen.getByLabelText('From');
    const to = screen.getByLabelText('To');
    const far = mondayPlusWeeks(20);
    await user.clear(from);
    await user.type(from, far);
    await user.clear(to);
    await user.type(to, far);
    expect(await screen.findByText('No team leave in this range.')).toBeInTheDocument();

    await user.clear(to);
    await user.type(to, mondayPlusWeeks(19));
    expect(screen.getByText('Select a valid date range')).toBeInTheDocument();
  });

  it('shows nothing for a staff member without reports', async () => {
    renderAs(SEED_ACCOUNTS.staff.email, <TeamCalendarTab />, '/leave/team-calendar');
    expect(await screen.findByText('No team leave in this range.')).toBeInTheDocument();
  });
});
