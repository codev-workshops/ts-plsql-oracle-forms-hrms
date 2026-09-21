import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { MOCK_USERS, seedRefreshSession } from '../../mocks/handlers';
import { renderWithProviders } from '../../test/render';
import { ProtectedRoute } from '../ProtectedRoute';

function routes() {
  return (
    <Routes>
      <Route path="/login" element={<h1>Login</h1>} />
      <Route path="/password" element={<h1>Password</h1>} />
      <Route path="/forbidden" element={<h1>Forbidden</h1>} />
      <Route element={<ProtectedRoute />}>
        <Route path="/" element={<h1>Home</h1>} />
      </Route>
      <Route element={<ProtectedRoute anyOf={['PAYROLL:VIEW']} />}>
        <Route path="/payroll" element={<h1>Payroll</h1>} />
      </Route>
    </Routes>
  );
}

describe('ProtectedRoute', () => {
  it('redirects anonymous users to /login after the silent refresh fails', async () => {
    renderWithProviders(routes(), { initialEntries: ['/'] });
    expect(screen.getByRole('status')).toHaveTextContent('Loading…');
    expect(await screen.findByRole('heading', { name: 'Login' })).toBeInTheDocument();
  });

  it('restores a session from the hrms_refresh cookie on reload', async () => {
    seedRefreshSession('staff@hrms.example');
    renderWithProviders(routes(), { initialEntries: ['/'] });
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
  });

  it('renders the outlet for an authenticated user', () => {
    renderWithProviders(routes(), { initialUser: MOCK_USERS['staff@hrms.example'], initialEntries: ['/'] });
    expect(screen.getByRole('heading', { name: 'Home' })).toBeInTheDocument();
  });

  it('sends users lacking the authority to /forbidden', () => {
    renderWithProviders(routes(), { initialUser: MOCK_USERS['staff@hrms.example'], initialEntries: ['/payroll'] });
    expect(screen.getByRole('heading', { name: 'Forbidden' })).toBeInTheDocument();
  });

  it('lets users holding one of the authorities through', () => {
    renderWithProviders(routes(), { initialUser: MOCK_USERS['admin@hrms.example'], initialEntries: ['/payroll'] });
    expect(screen.getByRole('heading', { name: 'Payroll' })).toBeInTheDocument();
  });

  it('forces mustChangePassword users onto /password', () => {
    renderWithProviders(routes(), { initialUser: MOCK_USERS['newhire@hrms.example'], initialEntries: ['/'] });
    expect(screen.getByRole('heading', { name: 'Password' })).toBeInTheDocument();
  });
});
