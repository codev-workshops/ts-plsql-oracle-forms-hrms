import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { setAccessToken } from '../../api/http';
import { MOCK_PASSWORD, MOCK_USERS, seedAccessToken } from '../../mocks/handlers';
import { renderWithProviders } from '../../test/render';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { ChangePasswordPage } from '../ChangePasswordPage';

function renderPage(email: string = SEED_ACCOUNTS.executive.email, entries = ['/password']) {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(
    <Routes>
      <Route path="/password" element={<ChangePasswordPage />} />
      <Route path="/" element={<h1>Home</h1>} />
    </Routes>,
    { initialUser: MOCK_USERS[email], initialEntries: entries },
  );
}

async function fill(current: string, next: string, confirm = next) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Current password'), current);
  await user.type(screen.getByLabelText('New password'), next);
  await user.type(screen.getByLabelText('Confirm new password'), confirm);
  await user.click(screen.getByRole('button', { name: 'Save' }));
}

describe('ChangePasswordPage', () => {
  it('lists the password policy from validation-schema.json in legacy order', () => {
    renderPage();
    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toEqual([
      'Password must be at least 8 characters',
      'Password must contain an uppercase letter',
      'Password must contain a number',
    ]);
    expect(screen.getByText('Minimum length: 8 characters.')).toBeInTheDocument();
  });

  it('applies the -20310 → -20311 → -20312 rules client-side, first failure only', async () => {
    renderPage();
    await fill(MOCK_PASSWORD, 'short');
    expect(await screen.findByRole('alert')).toHaveTextContent('Password must be at least 8 characters');

    const user = userEvent.setup();
    await user.clear(screen.getByLabelText('New password'));
    await user.clear(screen.getByLabelText('Confirm new password'));
    await user.type(screen.getByLabelText('New password'), 'lowercase1');
    await user.type(screen.getByLabelText('Confirm new password'), 'lowercase1');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Password must contain an uppercase letter'));
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('requires confirmation to match', async () => {
    renderPage();
    await fill(MOCK_PASSWORD, 'NewPass99', 'NewPass98');
    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
  });

  it('maps a server -20301 on currentPassword inline', async () => {
    renderPage();
    await fill('wrong-current', 'NewPass99');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Invalid username or password');
    expect(screen.getByLabelText('Current password')).toHaveAttribute('aria-invalid', 'true');
  });

  it('maps PASSWORD_REUSED to the newPassword field', async () => {
    renderPage();
    await fill(MOCK_PASSWORD, MOCK_PASSWORD);
    expect(await screen.findByRole('alert')).toHaveTextContent('New password must differ from the current password');
    expect(screen.getByLabelText('New password')).toHaveAttribute('aria-invalid', 'true');
  });

  it('PUTs /api/auth/password, toasts success and returns home', async () => {
    renderPage();
    await fill(MOCK_PASSWORD, 'NewPass99');
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Home' })).toBeInTheDocument());
    expect(screen.getByText('Password changed')).toBeInTheDocument();
  });

  it('shows the forced first-login variant without Cancel', () => {
    renderPage(SEED_ACCOUNTS.firstLogin.email);
    expect(screen.getByRole('heading', { name: 'Set your password' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('You must set a new password before continuing.');
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });
});
