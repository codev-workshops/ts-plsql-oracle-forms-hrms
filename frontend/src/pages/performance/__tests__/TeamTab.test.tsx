import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { setAccessToken } from '../../../api/http';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { renderWithProviders } from '../../../test/render';
import { TeamTab } from '../TeamTab';

function renderAs(email: string) {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(<TeamTab />, { initialUser: MOCK_USERS[email], initialEntries: ['/performance/team'] });
}

describe('TeamTab', () => {
  it('refreshes the team grid and rating distribution after the manager submits a review', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.manager.email);

    const cycle = await screen.findByLabelText('Cycle');
    await user.selectOptions(cycle, await screen.findByRole('option', { name: 'FY2025 Annual Review' }));
    const row = await screen.findByRole('row', { name: /EMILY JOHNSON/ });
    expect(within(row).getByText('MANAGER REVIEW')).toBeInTheDocument();
    expect(within(row).getAllByText('—')).toHaveLength(2);
    const distribution = screen.getByRole('heading', { name: 'Rating distribution' }).parentElement!;
    await waitFor(() => expect(within(distribution).queryByRole('status')).not.toBeInTheDocument());
    expect(within(distribution).queryByRole('row', { name: /Exceptional/ })).not.toBeInTheDocument();

    await user.click(within(row).getByRole('button', { name: 'Review' }));
    await user.clear(await screen.findByLabelText('Overall rating'));
    await user.type(screen.getByLabelText('Overall rating'), '4.6');
    await user.type(screen.getByLabelText('Manager assessment'), 'Excellent delivery.');
    await user.click(screen.getByRole('button', { name: 'Submit' }));
    await screen.findByText('Manager review submitted');

    await waitFor(() => expect(within(screen.getByRole('row', { name: /EMILY JOHNSON/ })).getAllByText('COMPLETED')).toHaveLength(1));
    const updated = screen.getByRole('row', { name: /EMILY JOHNSON/ });
    expect(within(updated).getByText('4.6')).toBeInTheDocument();
    expect(within(updated).getByText('Exceptional')).toBeInTheDocument();
    await waitFor(() => expect(within(distribution).getByRole('row', { name: /Exceptional/ })).toBeInTheDocument());
  });
});
