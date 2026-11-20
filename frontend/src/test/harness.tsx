import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import { setToken } from '../api/client';
import { App } from '../App';
import { AuthProvider, type CurrentUser, type Menu } from '../auth/AuthContext';

export type Handler = unknown | ((req: Request, body: unknown) => Response | unknown);
export interface Call {
  method: string;
  path: string;
  body: unknown;
}

export const user: CurrentUser = {
  empId: 1,
  empNumber: 'EMP-000001',
  username: 'ceo@hrms.example',
  firstName: 'Ada',
  lastName: 'King',
  loginTime: '2026-11-20T08:00:00Z',
  expiresAt: '2026-11-20T16:00:00Z',
  permissions: {
    EMPLOYEE: ['VIEW', 'EDIT'],
    LEAVE: ['VIEW', 'CREATE', 'APPROVE'],
    PERFORMANCE: ['VIEW', 'ADMIN'],
    PAYROLL: ['VIEW', 'EDIT', 'APPROVE'],
    REPORTS: ['VIEW'],
  },
};

export function menu(payroll = true, reports = true): Menu {
  return {
    modules: [
      { id: 'EMPLOYEES', label: 'Employee Management', module: 'EMPLOYEE', enabled: true, route: '/employees', apiPath: '/api/v1/employees' },
      { id: 'PAYROLL', label: 'Payroll', module: 'PAYROLL', enabled: payroll, route: '/payroll', apiPath: '/api/v1/payroll/runs' },
      { id: 'LEAVE', label: 'Leave Management', module: 'LEAVE', enabled: true, route: '/leave', apiPath: '/api/v1/leave/requests' },
      { id: 'PERFORMANCE', label: 'Performance', module: 'PERFORMANCE', enabled: true, route: '/performance', apiPath: '/api/v1/performance/reviews' },
      { id: 'REPORTS', label: 'Reports & Analytics', module: 'REPORTS', enabled: reports, route: '/reports', apiPath: '/api/v1/reports' },
    ],
    account: [
      { id: 'CHANGE_PASSWORD', label: 'Change Password', module: null, enabled: true, route: '/account/password', apiPath: '/api/v1/auth/change-password' },
      { id: 'LOGOUT', label: 'Logout', module: null, enabled: true, route: null, apiPath: '/api/v1/auth/logout' },
    ],
  };
}

export function json(status: number, data: unknown) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Mocks fetch with handlers keyed by "METHOD /api/v1/path" (query string ignored); records calls. */
export function mockApi(routes: Record<string, Handler>) {
  const calls: Call[] = [];
  const fetchMock = vi.fn(async (req: Request) => {
    const url = new URL(req.url);
    const text = req.method === 'GET' ? '' : await req.text();
    const body = text ? JSON.parse(text) : undefined;
    const key = `${req.method} ${url.pathname}`;
    calls.push({ method: req.method, path: url.pathname + url.search, body });
    if (!(key in routes)) return json(404, { code: 'NOT_FOUND', detail: `unmocked ${key}` });
    const h = routes[key];
    const out = typeof h === 'function' ? (h as (r: Request, b: unknown) => unknown)(req, body) : h;
    if (out instanceof Response) return out;
    return out === undefined ? new Response(null, { status: 204 }) : json(200, out);
  });
  vi.stubGlobal('fetch', fetchMock);
  return calls;
}

/** Renders the app already signed in (token + /me + /menu mocked) at {@code path}. */
export function renderSignedIn(path: string, routes: Record<string, Handler>, m: Menu = menu(), u: CurrentUser = user) {
  setToken('test-token');
  const calls = mockApi({ 'GET /api/v1/auth/me': u, 'GET /api/v1/menu': m, ...routes });
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
  return calls;
}
