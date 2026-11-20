import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { App } from '../App';
import { AuthProvider } from '../auth/AuthContext';
import { json, mockApi } from '../test/harness';

function renderAt(url: string) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

async function fill(password: string) {
  await userEvent.type(screen.getByLabelText('New password'), password);
  await userEvent.type(screen.getByLabelText('Repeat new password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Set password' }));
}

describe('password reset link (OQ-15, v1.5)', () => {
  it('sets the password from the token in the link fragment', async () => {
    const calls = mockApi({ 'POST /api/v1/auth/password-reset/confirm': new Response(null, { status: 204 }) });
    renderAt('/reset-password#token=abc123');
    await fill('Welcome#2026');
    expect(await screen.findByRole('status')).toHaveTextContent('Your password has been set');
    expect(calls[0]).toEqual({
      method: 'POST',
      path: '/api/v1/auth/password-reset/confirm',
      body: { token: 'abc123', newPassword: 'Welcome#2026' },
    });
  });

  it('shows INVALID_RESET_TOKEN for a used or expired link', async () => {
    mockApi({
      'POST /api/v1/auth/password-reset/confirm': json(422, { status: 422, code: 'INVALID_RESET_TOKEN' }),
    });
    renderAt('/reset-password#token=old');
    await fill('Welcome#2026');
    expect(await screen.findByRole('alert')).toHaveTextContent('INVALID_RESET_TOKEN');
  });
});
