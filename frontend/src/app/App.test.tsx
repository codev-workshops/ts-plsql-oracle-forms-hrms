import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { App } from './App';

afterEach(() => vi.unstubAllGlobals());

it('logs in and shows the module navigation for the user grade', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const body = url.endsWith('/auth/login')
      ? { token: 'abc', user: { empId: 22, username: 'thomas.baker@company.com', fullName: 'THOMAS BAKER',
          gradeLevel: 4, sessionId: 1 } }
      : [];
    return new Response(JSON.stringify(body), { status: 200 });
  }));
  const user = userEvent.setup();
  render(
    <MemoryRouter initialEntries={['/login']}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
  await user.type(screen.getByLabelText('Email'), 'thomas.baker@company.com');
  await user.type(screen.getByLabelText('Password'), 'Hrms#2026');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  expect(await screen.findByRole('heading', { name: 'Leave' })).toBeInTheDocument();
  const nav = screen.getByRole('navigation', { name: 'Main' });
  expect(nav).toHaveTextContent('Employees');
  expect(nav).not.toHaveTextContent('Payroll');
  expect(window.localStorage.getItem('hrms.token')).toBe('abc');
});
