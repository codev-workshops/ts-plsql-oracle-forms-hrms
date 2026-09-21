import { HttpResponse, http, type PathParams } from 'msw';
import type {
  ApiError,
  ChangePasswordRequest,
  CurrentUser,
  DepartmentRef,
  EmployeeSummary,
  JobTitleRef,
  LeaveTypeRef,
  LocationRef,
  LoginRequest,
  PageOfEmployeeSummary,
  TokenResponse,
} from '../api/types';
import { ROLE_AUTHORITIES, SEED_ACCOUNTS, SEED_PASSWORD } from '../../e2e/seed-accounts';
import { evaluateRules, getDto, getParameter } from '../validation/schema';

/**
 * msw implementation of contracts/p0-foundation/openapi.yaml, shared by Vitest (node) and
 * the Playwright smoke run (browser worker). Behaviour follows the contract descriptions
 * and error-codes.md exactly; it is a stand-in until the integration session runs the
 * real auth-service.
 */

const TRACE = '3f1c2d9e8b7a4c10';
const SESSION_TIMEOUT_MIN = Number(getParameter('SECURITY.SESSION_TIMEOUT_MIN'));

export const MOCK_PASSWORD = SEED_PASSWORD;

const seedUser = (account: (typeof SEED_ACCOUNTS)[keyof typeof SEED_ACCOUNTS], userId: string, deptId: number, jobTitle: string | null): CurrentUser => ({
  userId,
  empId: account.empId,
  empNumber: account.empNumber,
  email: account.email,
  firstName: account.firstName,
  lastName: account.lastName,
  displayName: account.displayName,
  deptId,
  jobTitle,
  roles: ROLE_AUTHORITIES[account.role],
  mustChangePassword: account.mustChangePassword,
});

export const MOCK_USERS: Record<string, CurrentUser> = {
  [SEED_ACCOUNTS.executive.email]: seedUser(SEED_ACCOUNTS.executive, 'ua-1', 1, 'CEO'),
  [SEED_ACCOUNTS.manager.email]: seedUser(SEED_ACCOUNTS.manager, 'ua-2', 20, 'Manager'),
  [SEED_ACCOUNTS.staff.email]: seedUser(SEED_ACCOUNTS.staff, 'ua-3', 10, 'Analyst'),
  [SEED_ACCOUNTS.firstLogin.email]: seedUser(SEED_ACCOUNTS.firstLogin, 'ua-4', 10, null),
};

export const MOCK_DEPARTMENTS: DepartmentRef[] = [
  { deptId: 1, deptCode: 'HR', deptName: 'Human Resources', parentDeptId: null, locationCode: 'HQ', active: true },
  { deptId: 2, deptCode: 'FIN', deptName: 'Finance', parentDeptId: null, locationCode: 'HQ', active: true },
  { deptId: 3, deptCode: 'ENG', deptName: 'Engineering', parentDeptId: null, locationCode: 'SF', active: true },
  { deptId: 9, deptCode: 'OLD', deptName: 'Retired Dept', parentDeptId: null, locationCode: 'CHI', active: false },
];

export const MOCK_JOB_TITLES: JobTitleRef[] = [
  { jobId: 1, jobCode: 'ANL', jobTitle: 'Analyst', jobFamily: 'Finance', gradeId: 3, gradeCode: 'G3', gradeName: 'Grade 3', gradeMinSalary: '45000.00', gradeMaxSalary: '65000.00', active: true },
  { jobId: 2, jobCode: 'HRD', jobTitle: 'HR Director', jobFamily: 'HR', gradeId: 9, gradeCode: 'G9', gradeName: 'Grade 9', gradeMinSalary: '120000.00', gradeMaxSalary: '180000.00', active: true },
];

export const MOCK_LOCATIONS: LocationRef[] = [
  { locationCode: 'HQ', locationName: 'Headquarters', city: 'New York', stateProvince: 'NY', countryCode: 'USA', timezone: 'America/New_York', active: true },
  { locationCode: 'SF', locationName: 'San Francisco', city: 'San Francisco', stateProvince: 'CA', countryCode: 'USA', timezone: 'America/Los_Angeles', active: true },
  { locationCode: 'CHI', locationName: 'Chicago', city: 'Chicago', stateProvince: 'IL', countryCode: 'USA', timezone: 'America/Chicago', active: true },
];

export const MOCK_LEAVE_TYPES: LeaveTypeRef[] = [
  { leaveTypeId: 1, leaveTypeCode: 'PTO', leaveTypeName: 'Paid Time Off', paid: true, accrual: true, accrualRate: '1.25', maxBalance: '30.00', carryoverMax: '5.00', minTenureDays: 0, requiresApproval: true, requiresDocument: false, active: true },
  { leaveTypeId: 2, leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', paid: true, accrual: true, accrualRate: '0.83', maxBalance: '10.00', carryoverMax: null, minTenureDays: 0, requiresApproval: false, requiresDocument: true, active: true },
];

export const MOCK_EMPLOYEES: EmployeeSummary[] = [
  { id: 1, empNumber: 'EMP-000001', name: 'JAMES RICHARDSON', jobTitle: 'CEO' },
  { id: 11, empNumber: 'EMP-000011', name: 'DAVID MARTINEZ', jobTitle: 'Analyst' },
  { id: 4, empNumber: 'EMP-000004', name: 'MIA MANAGER', jobTitle: null },
];

interface Session {
  email: string;
  accessToken: string;
  refreshToken: string;
}

const sessions = new Map<string, Session>(); // by accessToken
const refreshTokens = new Map<string, Session>(); // by refreshToken
const passwords = new Map<string, string>(Object.keys(MOCK_USERS).map((e) => [e, MOCK_PASSWORD]));
let seq = 0;
let refreshCookieForTests: string | null = null; // node has no cookie jar

export function resetMockState() {
  sessions.clear();
  refreshTokens.clear();
  passwords.clear();
  for (const e of Object.keys(MOCK_USERS)) passwords.set(e, MOCK_PASSWORD);
  seq = 0;
  refreshCookieForTests = null;
}

/** Test helper: pretend the browser holds a valid `hrms_refresh` cookie for this user. */
export function seedRefreshSession(email: string): Session {
  const s = createSession(email);
  refreshCookieForTests = s.refreshToken;
  return s;
}

/** Test helper: obtain an access token for a user (no HTTP). */
export function seedAccessToken(email: string): string {
  return createSession(email).accessToken;
}

/** Test helper: revoke a token so the next call returns 401 TOKEN_INVALID. */
export function revokeAccessToken(token: string) {
  sessions.delete(token);
}

function createSession(email: string): Session {
  seq += 1;
  const s: Session = { email, accessToken: `access-${email}-${seq}`, refreshToken: `refresh-${email}-${seq}` };
  sessions.set(s.accessToken, s);
  refreshTokens.set(s.refreshToken, s);
  return s;
}

function apiError(status: number, body: Omit<ApiError, 'traceId'>, headers?: Record<string, string>) {
  return HttpResponse.json<ApiError>({ ...body, traceId: TRACE }, { status, headers });
}

const unauthorized = () => apiError(401, { code: 'TOKEN_INVALID', message: 'Session has expired' });
const forbidden = () => apiError(403, { code: 'FORBIDDEN', message: 'You do not have permission to perform this action' });

function tokenResponse(s: Session): TokenResponse {
  return { accessToken: s.accessToken, tokenType: 'Bearer', expiresIn: SESSION_TIMEOUT_MIN * 60, user: MOCK_USERS[s.email] };
}

function refreshCookie(s: Session) {
  return `hrms_refresh=${s.refreshToken}; HttpOnly; Secure; SameSite=Strict; Path=/api/auth`;
}

function authenticate(request: Request): Session | null {
  const h = request.headers.get('authorization');
  if (!h?.startsWith('Bearer ')) return null;
  return sessions.get(h.slice(7)) ?? null;
}

function readRefreshCookie(request: Request): string | null {
  const cookie = request.headers.get('cookie') ?? '';
  const m = /(?:^|;\s*)hrms_refresh=([^;]+)/.exec(cookie);
  // The in-memory value wins: jsdom keeps a cookie jar across tests, so a stale header may linger.
  return refreshCookieForTests ?? m?.[1] ?? null;
}

function parseActive(url: URL): boolean | null {
  const v = url.searchParams.get('active');
  if (v === null || v === 'true') return true;
  if (v === 'false') return false;
  return null;
}

function referenceHandler<T extends { active: boolean }>(path: string, rows: T[]) {
  return http.get(path, ({ request }) => {
    if (!authenticate(request)) return unauthorized();
    const active = parseActive(new URL(request.url));
    if (active === null) return apiError(400, { code: 'VALIDATION_FAILED', message: 'Request validation failed', field: 'active' });
    return HttpResponse.json(active ? rows.filter((r) => r.active) : rows, {
      headers: { 'Cache-Control': 'private, max-age=300', ETag: `"${path}-${rows.length}"` },
    });
  });
}

const failedLogins = new Map<string, number>();

export const handlers = [
  http.post<PathParams, LoginRequest>('/api/auth/login', async ({ request }) => {
    const body = await request.json();
    const username = body.username?.trim().toLowerCase();
    if (!username || !body.password) {
      return apiError(400, {
        code: 'VALIDATION_FAILED',
        message: 'Request validation failed',
        field: !username ? 'username' : 'password',
        details: [{ field: !username ? 'username' : 'password', code: 'NotBlank', message: 'must not be blank' }],
      });
    }
    const failures = failedLogins.get(username) ?? 0;
    if (failures >= 5) return apiError(429, { code: 'RATE_LIMITED', message: 'Too many failed attempts' }, { 'Retry-After': '900' });
    const user = MOCK_USERS[username];
    if (!user || passwords.get(username) !== body.password) {
      failedLogins.set(username, failures + 1);
      return apiError(401, { code: '-20301', message: 'Invalid username or password' });
    }
    failedLogins.delete(username);
    const s = createSession(username);
    refreshCookieForTests = s.refreshToken;
    return HttpResponse.json(tokenResponse(s), { headers: { 'Set-Cookie': refreshCookie(s) } });
  }),

  http.post('/api/auth/logout', ({ request }) => {
    const s = authenticate(request);
    if (s) {
      sessions.delete(s.accessToken);
      refreshTokens.delete(s.refreshToken);
    }
    refreshCookieForTests = null;
    return new HttpResponse(null, { status: 204, headers: { 'Set-Cookie': 'hrms_refresh=; Max-Age=0; Path=/api/auth' } });
  }),

  http.post('/api/auth/refresh', ({ request }) => {
    const rt = readRefreshCookie(request);
    const old = rt ? refreshTokens.get(rt) : undefined;
    if (!old) return unauthorized();
    refreshTokens.delete(old.refreshToken);
    const s = createSession(old.email);
    refreshCookieForTests = s.refreshToken;
    return HttpResponse.json(tokenResponse(s), { headers: { 'Set-Cookie': refreshCookie(s) } });
  }),

  http.get('/api/auth/me', ({ request }) => {
    const s = authenticate(request);
    if (!s) return unauthorized();
    const user = MOCK_USERS[s.email];
    if (!user) return apiError(404, { code: '-20001', message: 'Employee not found or not active' });
    return HttpResponse.json(user);
  }),

  http.put<PathParams, ChangePasswordRequest>('/api/auth/password', async ({ request }) => {
    const s = authenticate(request);
    if (!s) return unauthorized();
    const body = await request.json();
    if (!body.currentPassword || !body.newPassword) {
      return apiError(400, { code: 'VALIDATION_FAILED', message: 'Request validation failed', field: !body.currentPassword ? 'currentPassword' : 'newPassword' });
    }
    if (passwords.get(s.email) !== body.currentPassword) {
      return apiError(401, { code: '-20301', message: 'Invalid username or password', field: 'currentPassword' });
    }
    const failure = evaluateRules(getDto('ChangePasswordRequest').fields.newPassword, body.newPassword);
    if (failure) return apiError(400, { code: failure.errorCode, message: failure.message, field: 'newPassword' });
    if (body.newPassword === body.currentPassword) {
      return apiError(400, { code: 'PASSWORD_REUSED', message: 'New password must differ from the current password', field: 'newPassword' });
    }
    passwords.set(s.email, body.newPassword);
    MOCK_USERS[s.email] = { ...MOCK_USERS[s.email], mustChangePassword: false };
    for (const [tok, other] of sessions) if (other.email === s.email && tok !== s.accessToken) sessions.delete(tok);
    return new HttpResponse(null, { status: 204 });
  }),

  referenceHandler('/api/reference/departments', MOCK_DEPARTMENTS),
  referenceHandler('/api/reference/job-titles', MOCK_JOB_TITLES),
  referenceHandler('/api/reference/locations', MOCK_LOCATIONS),
  referenceHandler('/api/reference/leave-types', MOCK_LEAVE_TYPES),

  http.get('/api/employees', ({ request }) => {
    const s = authenticate(request);
    if (!s) return unauthorized();
    if (!MOCK_USERS[s.email].roles.includes('EMPLOYEE:VIEW')) return forbidden();
    const url = new URL(request.url);
    if (url.searchParams.get('status') !== 'ACTIVE' || url.searchParams.get('fields') !== 'id,name,jobTitle') {
      return apiError(400, { code: 'VALIDATION_FAILED', message: 'Request validation failed', field: 'status' });
    }
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase();
    if (q.length > 100) return apiError(400, { code: 'VALIDATION_FAILED', message: 'Request validation failed', field: 'q' });
    const excludeSelf = url.searchParams.get('excludeSelf') === 'true';
    const page = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? 20);
    if (page < 0 || size < 1 || size > 100) return apiError(400, { code: 'VALIDATION_FAILED', message: 'Request validation failed', field: 'size' });
    let rows = MOCK_EMPLOYEES.filter((e) => !q || e.name.toLowerCase().includes(q) || e.empNumber.toLowerCase().includes(q));
    if (excludeSelf) rows = rows.filter((e) => e.id !== MOCK_USERS[s.email].empId);
    const body: PageOfEmployeeSummary = {
      content: rows.slice(page * size, page * size + size),
      page,
      size,
      totalElements: rows.length,
      totalPages: Math.ceil(rows.length / size),
    };
    return HttpResponse.json(body);
  }),
];
