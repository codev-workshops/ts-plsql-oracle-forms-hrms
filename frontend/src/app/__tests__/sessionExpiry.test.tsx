import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { getAccessToken, setAccessToken } from '../../api/http';
import { MOCK_PASSWORD, MOCK_USERS, revokeAccessToken, seedAccessToken, seedRefreshSession } from '../../mocks/handlers';
import { renderWithProviders } from '../../test/render';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { AppRoutes } from '../../App';
import { SESSION_EXPIRED_MESSAGE } from '../AuthContext';

/**
 * App-level check of error-codes.md §3 invariant 4 / COMPONENT_MAPPING.md §7 `check_session`:
 * a 401 TOKEN_INVALID on a protected call is retried once through `/api/auth/refresh`; when
 * the refresh also fails the shell drops to /login with the "Session has expired" message.
 */
describe('session expiry (AuthContext + axios interceptor + ProtectedRoute)', () => {
  const email = SEED_ACCOUNTS.executive.email;

  async function submitPasswordChange() {
    const user = userEvent.setup();
    await user.click(screen.getByRole('link', { name: 'Change password' }));
    await user.type(await screen.findByLabelText('Current password'), MOCK_PASSWORD);
    await user.type(screen.getByLabelText('New password', { exact: true }), 'Stronger9!');
    await user.type(screen.getByLabelText('Confirm new password'), 'Stronger9!');
    await user.click(screen.getByRole('button', { name: 'Save' }));
  }

  it('sends the user to /login with the expiry message when the access token is revoked and no refresh cookie exists', async () => {
    const token = seedAccessToken(email);
    setAccessToken(token);
    revokeAccessToken(token);
    renderWithProviders(<AppRoutes />, { initialUser: MOCK_USERS[email] });

    await submitPasswordChange();

    expect(await screen.findByLabelText('E-mail')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(SESSION_EXPIRED_MESSAGE);
    expect(getAccessToken()).toBeNull();
  });

  it('silently refreshes through the hrms_refresh cookie and replays the request', async () => {
    const token = seedAccessToken(email);
    setAccessToken(token);
    revokeAccessToken(token);
    seedRefreshSession(email);
    renderWithProviders(<AppRoutes />, { initialUser: MOCK_USERS[email] });

    await submitPasswordChange();

    expect(await screen.findByText('Password changed')).toBeInTheDocument();
    expect(screen.queryByLabelText('E-mail')).not.toBeInTheDocument();
    expect(getAccessToken()).not.toBeNull();
    expect(getAccessToken()).not.toBe(token);
  });
});
