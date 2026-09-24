import { screen } from '@testing-library/react';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { setAccessToken } from '../../../api/http';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { renderWithProviders } from '../../../test/render';
import { GoalsTab } from '../GoalsTab';

describe('GoalsTab', () => {
  it('shows the empty state when the caller has no reviews', async () => {
    const email = SEED_ACCOUNTS.executive.email;
    setAccessToken(seedAccessToken(email));
    renderWithProviders(<GoalsTab />, {
      initialUser: MOCK_USERS[email],
      initialEntries: ['/performance/goals'],
    });

    expect(await screen.findByText('No reviews')).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Review')).not.toBeInTheDocument();
  });
});
