import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { App } from './App';
import { AuthProvider } from './auth/AuthContext';
import { json, menu, mockApi, renderSignedIn, user } from './test/harness';

function renderLoggedOut() {
  render(
    <MemoryRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('login', () => {
  it('signs in with email + password and renders the menu', async () => {
    const calls = mockApi({
      'POST /api/v1/auth/login': { accessToken: 'tok', tokenType: 'Bearer', expiresAt: user.expiresAt, user },
      'GET /api/v1/auth/me': user,
      'GET /api/v1/menu': menu(),
      'GET /api/v1/employees': { items: [], page: 0, size: 50, total: 0 },
      'GET /api/v1/lookups/departments': [],
      'GET /api/v1/lookups/job-titles': [],
      'GET /api/v1/lookups/locations': [],
    });
    renderLoggedOut();
    await userEvent.type(screen.getByLabelText('Work email'), 'ceo@hrms.example');
    await userEvent.type(screen.getByLabelText('Password'), 'Hrms#2026');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('link', { name: 'Employee Management' })).toBeInTheDocument();
    expect(calls[0]).toEqual({
      method: 'POST',
      path: '/api/v1/auth/login',
      body: { username: 'ceo@hrms.example', password: 'Hrms#2026' },
    });
    expect(sessionStorage.getItem('hrms.token')).toBe('tok');
  });

  it('shows the INVALID_CREDENTIALS contract error', async () => {
    mockApi({
      'POST /api/v1/auth/login': json(401, { status: 401, code: 'INVALID_CREDENTIALS', detail: 'Invalid username or password' }),
    });
    renderLoggedOut();
    await userEvent.type(screen.getByLabelText('Work email'), 'ceo@hrms.example');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('INVALID_CREDENTIALS');
  });

  it('returns to the login screen when the session expires (401 UNAUTHENTICATED)', async () => {
    renderSignedIn('/leave', {
      'GET /api/v1/leave/types': json(401, { code: 'UNAUTHENTICATED' }),
      'GET /api/v1/leave/balances': [],
      'GET /api/v1/leave/requests': [],
      'GET /api/v1/leave/approvals': [],
    });
    expect(await screen.findByText('Your session has expired. Please sign in again.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('logs out through the account menu', async () => {
    const calls = renderSignedIn('/account/password', { 'POST /api/v1/auth/logout': undefined });
    await userEvent.click(await screen.findByRole('button', { name: 'Logout' }));
    expect(await screen.findByText('You have signed out.')).toBeInTheDocument();
    expect(calls.some((c) => c.path === '/api/v1/auth/logout')).toBe(true);
  });
});

describe('navigation', () => {
  it('renders disabled menu entries as non-links and never shows Admin', async () => {
    renderSignedIn('/account/password', {}, menu(false, false));
    const nav = await screen.findByRole('navigation', { name: 'Main menu' });
    await within(nav).findByRole('link', { name: 'Employee Management' });
    expect(within(nav).queryByRole('link', { name: 'Payroll' })).toBeNull();
    expect(within(nav).getByText('Payroll')).toHaveAttribute('aria-disabled', 'true');
    expect(within(nav).queryByRole('link', { name: 'Reports & Analytics' })).toBeNull();
    expect(within(nav).queryByText(/admin/i)).toBeNull();
    expect(within(nav).getByRole('link', { name: 'Change Password' })).toBeInTheDocument();
  });

  it('changes the password', async () => {
    const calls = renderSignedIn('/account/password', { 'POST /api/v1/auth/change-password': undefined });
    await userEvent.type(await screen.findByLabelText(/Current password/), 'Hrms#2026');
    await userEvent.type(screen.getByLabelText(/New password/), 'N3w#Passw0rd');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    await screen.findByText('Change password: done');
    expect(calls.find((c) => c.path === '/api/v1/auth/change-password')?.body).toEqual({
      currentPassword: 'Hrms#2026',
      newPassword: 'N3w#Passw0rd',
    });
  });
});
