import { HttpResponse, http as mswHttp } from 'msw';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { api } from '../client';
import { bindSessionHandlers, getAccessToken, setAccessToken } from '../http';
import { revokeAccessToken, seedAccessToken, seedRefreshSession } from '../../mocks/handlers';
import { server } from '../../mocks/server';

describe('axios interceptor (401 → refresh → replay | /login)', () => {
  it('attaches the in-memory bearer token', async () => {
    setAccessToken(seedAccessToken(SEED_ACCOUNTS.staff.email));
    const me = await api.auth.me();
    expect(me.email).toBe(SEED_ACCOUNTS.staff.email);
  });

  it('refreshes once on 401 TOKEN_INVALID and replays the request', async () => {
    const s = seedRefreshSession(SEED_ACCOUNTS.staff.email);
    setAccessToken(s.accessToken);
    revokeAccessToken(s.accessToken);
    const refreshed = vi.fn();
    bindSessionHandlers({ onTokenRefreshed: refreshed, onSessionExpired: vi.fn() });

    const me = await api.auth.me();
    expect(me.email).toBe(SEED_ACCOUNTS.staff.email);
    expect(getAccessToken()).not.toBe(s.accessToken);
    expect(refreshed).toHaveBeenCalledTimes(1);
  });

  it('deduplicates concurrent refreshes', async () => {
    const s = seedRefreshSession(SEED_ACCOUNTS.staff.email);
    setAccessToken(s.accessToken);
    revokeAccessToken(s.accessToken);
    let refreshCalls = 0;
    server.events.on('request:start', ({ request }) => {
      if (request.url.endsWith('/api/auth/refresh')) refreshCalls += 1;
    });
    await Promise.all([api.auth.me(), api.reference.listDepartments({ active: true }), api.reference.listLocations({ active: true })]);
    expect(refreshCalls).toBe(1);
    server.events.removeAllListeners();
  });

  it('clears the session and notifies when the refresh itself fails', async () => {
    setAccessToken('stale-token');
    const expired = vi.fn();
    bindSessionHandlers({ onTokenRefreshed: vi.fn(), onSessionExpired: expired });
    await expect(api.auth.me()).rejects.toMatchObject({ response: { status: 401 } });
    expect(getAccessToken()).toBeNull();
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it('does not attempt a refresh for a 401 from the auth endpoints themselves', async () => {
    let refreshCalls = 0;
    server.events.on('request:start', ({ request }) => {
      if (request.url.endsWith('/api/auth/refresh')) refreshCalls += 1;
    });
    await expect(api.auth.login({ username: SEED_ACCOUNTS.executive.email, password: 'nope' })).rejects.toMatchObject({
      response: { status: 401, data: { code: '-20301' } },
    });
    expect(refreshCalls).toBe(0);
    server.events.removeAllListeners();
  });

  it('passes non-401 ApiErrors straight through', async () => {
    setAccessToken(seedAccessToken(SEED_ACCOUNTS.staff.email));
    server.use(
      mswHttp.get('/api/reference/departments', () =>
        HttpResponse.json({ code: 'FORBIDDEN', message: 'nope', traceId: 't' }, { status: 403 }),
      ),
    );
    await expect(api.reference.listDepartments({ active: true })).rejects.toMatchObject({ response: { data: { code: 'FORBIDDEN' } } });
  });
});
