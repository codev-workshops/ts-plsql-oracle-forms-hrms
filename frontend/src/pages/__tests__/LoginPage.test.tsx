import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { getAccessToken } from '../../api/http';
import { MOCK_PASSWORD } from '../../mocks/handlers';
import { server } from '../../mocks/server';
import { renderWithProviders } from '../../test/render';
import { LoginPage } from '../LoginPage';

function renderLogin(entries = ['/login']) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<h1>Home</h1>} />
      <Route path="/password" element={<h1>Set your password</h1>} />
      <Route path="/employees" element={<h1>Employees</h1>} />
    </Routes>,
    { initialEntries: entries },
  );
}

describe('LoginPage', () => {
  it('renders username/password and validates from the generated schema before calling the API', async () => {
    const user = userEvent.setup();
    renderLogin();
    await screen.findByLabelText('E-mail');

    await user.click(screen.getByRole('button', { name: 'Login' }));
    const alerts = await screen.findAllByRole('alert');
    expect(alerts.map((a) => a.textContent)).toEqual(['Username is required', 'Password is required']);

    await user.type(screen.getByLabelText('E-mail'), 'not-an-email');
    await user.type(screen.getByLabelText('Password'), 'x');
    await user.click(screen.getByRole('button', { name: 'Login' }));
    expect(await screen.findByText('Enter a valid e-mail address')).toBeInTheDocument();
    expect(getAccessToken()).toBeNull();
  });

  it('signs in through POST /api/auth/login, keeps the token in memory and redirects home', async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.type(await screen.findByLabelText('E-mail'), 'admin@hrms.example');
    await user.type(screen.getByLabelText('Password'), `${MOCK_PASSWORD}{enter}`);

    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
    expect(getAccessToken()).toMatch(/^access-admin@hrms.example/);
    // Nothing of ours is persisted (the only key is msw's internal cookie store).
    expect(Object.keys(window.localStorage).filter((k) => !k.startsWith('__msw'))).toEqual([]);
    expect(JSON.stringify(window.localStorage)).not.toContain('access-admin');
  });

  it('shows the uniform -20301 message inline for bad credentials', async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.type(await screen.findByLabelText('E-mail'), 'admin@hrms.example');
    await user.type(screen.getByLabelText('Password'), 'wrong{enter}');

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password');
    expect(getAccessToken()).toBeNull();
  });

  it('shows RATE_LIMITED inline', async () => {
    server.use(
      http.post('/api/auth/login', () =>
        HttpResponse.json({ code: 'RATE_LIMITED', message: 'Too many failed attempts', traceId: 't' }, { status: 429 }),
      ),
    );
    const user = userEvent.setup();
    renderLogin();
    await user.type(await screen.findByLabelText('E-mail'), 'admin@hrms.example');
    await user.type(screen.getByLabelText('Password'), 'whatever{enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many failed attempts');
  });

  it('routes a mustChangePassword user to /password', async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.type(await screen.findByLabelText('E-mail'), 'newhire@hrms.example');
    await user.type(screen.getByLabelText('Password'), `${MOCK_PASSWORD}{enter}`);
    expect(await screen.findByRole('heading', { name: 'Set your password' })).toBeInTheDocument();
  });

  it('returns to the originally requested page after login', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/employees" element={<h1>Employees</h1>} />
      </Routes>,
      { initialEntries: [{ pathname: '/login', state: { from: { pathname: '/employees' } } } as unknown as string] },
    );
    await user.type(await screen.findByLabelText('E-mail'), 'staff@hrms.example');
    await user.type(screen.getByLabelText('Password'), `${MOCK_PASSWORD}{enter}`);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Employees' })).toBeInTheDocument());
  });
});
