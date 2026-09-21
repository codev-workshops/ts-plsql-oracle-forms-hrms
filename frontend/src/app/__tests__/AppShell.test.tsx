import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { getAccessToken, setAccessToken } from '../../api/http';
import { MOCK_USERS, seedAccessToken } from '../../mocks/handlers';
import { renderWithProviders } from '../../test/render';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { AppRoutes } from '../../App';

describe('AppShell', () => {
  it('renders brand, authority-filtered nav, user info and logs out via POST /api/auth/logout', async () => {
    setAccessToken(seedAccessToken(SEED_ACCOUNTS.staff.email));
    renderWithProviders(<AppRoutes />, { initialUser: MOCK_USERS[SEED_ACCOUNTS.staff.email] });

    const nav = screen.getByRole('navigation', { name: 'Modules' });
    expect(within(nav).getAllByRole('link').map((l) => l.textContent)).toEqual(['Employees', 'Leave', 'Performance']);
    expect(screen.getByTestId('user-info')).toHaveTextContent('DAVID MARTINEZ');
    expect(screen.getByRole('link', { name: 'Change password' })).toHaveAttribute('href', '/password');

    await userEvent.setup().click(screen.getByRole('button', { name: 'Logout' }));
    await waitFor(() => expect(screen.getByLabelText('E-mail')).toBeInTheDocument());
    expect(getAccessToken()).toBeNull();
  });
});
