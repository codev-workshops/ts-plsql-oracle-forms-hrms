import { configure, screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { getAccessToken } from '../../api/http';
import { MOCK_USERS, seedRefreshSession } from '../../mocks/handlers';
import { server } from '../../mocks/server';
import { renderWithProviders } from '../../test/render';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { ProtectedRoute } from '../ProtectedRoute';
import { SESSION_EXPIRED_MESSAGE } from '../AuthContext';

function routes() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <>
            <h1>Login</h1>
            <p role="status">{SESSION_EXPIRED_MESSAGE}</p>
          </>
        }
      />
      <Route element={<ProtectedRoute />}>
        <Route path="/" element={<h1>Home</h1>} />
      </Route>
    </Routes>
  );
}

describe('StrictMode bootstrap refresh (single-flight)', () => {
  beforeEach(() => configure({ reactStrictMode: true }));
  afterEach(() => configure({ reactStrictMode: false }));

  it('sends exactly one POST /api/auth/refresh on mount and keeps the restored session', async () => {
    const seeded = seedRefreshSession(SEED_ACCOUNTS.staff.email);
    let refreshCalls = 0;
    let consumed = false;
    server.use(
      http.post('/api/auth/refresh', () => {
        refreshCalls += 1;
        if (consumed) {
          return HttpResponse.json({ code: 'TOKEN_INVALID', message: 'Refresh token replayed', traceId: 't' }, { status: 401 });
        }
        consumed = true;
        return HttpResponse.json({
          accessToken: `rotated-${seeded.accessToken}`,
          tokenType: 'Bearer',
          expiresIn: 1800,
          user: MOCK_USERS[SEED_ACCOUNTS.staff.email],
        });
      }),
    );

    renderWithProviders(routes(), { initialEntries: ['/'] });

    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
    expect(refreshCalls).toBe(1);
    expect(getAccessToken()).toBe(`rotated-${seeded.accessToken}`);
    expect(screen.queryByRole('heading', { name: 'Login' })).not.toBeInTheDocument();
  });
});
