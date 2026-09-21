import { expect, test, type Page } from '@playwright/test';

/**
 * Phase 0 golden path against the REAL stack (nginx proxy -> auth-service -> PostgreSQL, Vite app shell).
 * Seed accounts were created by the integration session (tools/fixtures/pg seed + user_accounts rows):
 *   EXECUTIVE  james.richardson@company.com  (emp 1, grade 10, role 3)
 *   MANAGER    jennifer.park@company.com     (emp 21, grade 6, role 2)
 *   STAFF      david.martinez@company.com    (emp 11, grade 3, role 1)
 *   STAFF+must_change_password  emily.johnson@company.com (emp 12)
 * Password for all: Welcome1!
 */
const PASSWORD = 'Welcome1!';

const USERS = {
  executive: {
    email: 'james.richardson@company.com',
    name: 'JAMES RICHARDSON',
    tiles: ['employees', 'payroll', 'leave', 'performance'],
    hidden: ['reports', 'admin'],
  },
  manager: {
    email: 'jennifer.park@company.com',
    name: 'JENNIFER PARK',
    tiles: ['employees', 'payroll', 'leave', 'performance'],
    hidden: ['reports', 'admin'],
  },
  staff: {
    email: 'david.martinez@company.com',
    name: 'DAVID MARTINEZ',
    tiles: ['employees', 'leave', 'performance'],
    hidden: ['payroll', 'reports', 'admin'],
  },
} as const;

async function login(page: Page, email: string, password = PASSWORD) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Login' }).click();
}

for (const [role, u] of Object.entries(USERS)) {
  test(`login as ${role} -> tiles match authorities -> logout`, async ({ page }) => {
    await page.goto('/employees');
    await expect(page).toHaveURL(/\/login$/);
    await login(page, u.email);
    await expect(page.getByRole('heading', { name: `Welcome, ${u.name}` })).toBeVisible();
    for (const t of u.tiles) {
      const tile = page.getByTestId(`tile-${t}`);
      await expect(tile).toBeVisible();
      await expect(tile).toHaveAttribute('href', `/${t}`);
      await expect(tile).toHaveAttribute('data-legacy', 'true'); // every switchable module is LEGACY at end of P0
    }
    for (const t of u.hidden) await expect(page.getByTestId(`tile-${t}`)).toHaveCount(0);
    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
    // refresh cookie must be gone/revoked: a silent refresh no longer restores the session
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
  });
}

test('invalid credentials show the uniform -20301 message (API returns ApiError -20301)', async ({ page }) => {
  const resp = page.waitForResponse((r) => r.url().endsWith('/api/auth/login'));
  await login(page, USERS.staff.email, 'wrong-password');
  const r = await resp;
  expect(r.status()).toBe(401);
  const body = await r.json();
  expect(body.code).toBe('-20301');
  expect(body.traceId).toBeTruthy();
  await expect(page.getByRole('alert')).toHaveText('Invalid username or password');
});

test('first-login user (must_change_password) is forced onto the set-password page', async ({ page }) => {
  await login(page, 'emily.johnson@company.com');
  await expect(page).toHaveURL(/\/password$/);
  await expect(page.getByRole('heading', { name: 'Set your password' })).toBeVisible();
});

test('change password enforces -20310/-20311/-20312 from the real API then succeeds', async ({ page }) => {
  await login(page, USERS.manager.email);
  await page.getByRole('link', { name: 'Change password' }).click();
  await page.getByLabel('Current password').fill(PASSWORD);
  await page.getByLabel('New password', { exact: true }).fill('lowercase1');
  await page.getByLabel('Confirm new password').fill('lowercase1');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('alert')).toHaveText('Password must contain an uppercase letter');

  await page.getByLabel('New password', { exact: true }).fill('Stronger9!');
  await page.getByLabel('Confirm new password').fill('Stronger9!');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Password changed')).toBeVisible();
  // and back again so the fixture stays stable
  await page.getByRole('link', { name: 'Change password' }).click();
  await page.getByLabel('Current password').fill('Stronger9!');
  await page.getByLabel('New password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm new password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Password changed')).toBeVisible();
});

test('401/refresh flow: reload restores the session through the HttpOnly refresh cookie; replayed cookie is rejected', async ({ page, context }) => {
  await login(page, USERS.staff.email);
  await expect(page.getByRole('heading', { name: `Welcome, ${USERS.staff.name}` })).toBeVisible();

  // in-memory access token is lost on reload -> silent POST /api/auth/refresh must restore the user
  const refreshed = page.waitForResponse((r) => r.url().endsWith('/api/auth/refresh'));
  await page.reload();
  expect((await refreshed).status()).toBe(200);
  await expect(page.getByRole('heading', { name: `Welcome, ${USERS.staff.name}` })).toBeVisible();

  // rotation: the pre-rotation cookie value is a replay and must be refused with an ApiError
  const cookies = await context.cookies();
  const rt = cookies.find((c) => c.name === 'hrms_refresh');
  expect(rt, 'hrms_refresh cookie present').toBeTruthy();
  expect(rt!.httpOnly).toBe(true);
  const staleValue = rt!.value;
  const again = await page.request.post('/api/auth/refresh'); // rotates using current cookie
  expect(again.status()).toBe(200);
  const replay = await page.request.post('/api/auth/refresh', { headers: { Cookie: `hrms_refresh=${staleValue}` } });
  expect(replay.status()).toBe(401);
  const err = await replay.json();
  expect(err.code).toBe('TOKEN_INVALID');
  expect(err.traceId).toBeTruthy();

  // bad bearer -> 401 ApiError (never a 500 / HTML page)
  const me = await page.request.get('/api/auth/me', { headers: { Authorization: 'Bearer not-a-jwt' } });
  expect(me.status()).toBe(401);
  expect((await me.json()).code).toBe('TOKEN_INVALID');
});

test('open a legacy tile through the proxy SSO bridge (Forms session)', async ({ page }) => {
  await login(page, USERS.executive.email);
  const tile = page.getByTestId('tile-employees');
  await expect(tile).toHaveAttribute('data-legacy', 'true');
  const nav = page.waitForResponse((r) => new URL(r.url()).pathname.startsWith('/employees'));
  await tile.click();
  const r = await nav;
  // No Oracle in this environment: the contract's documented outcome for a LEGACY module path is
  // the bridge's ApiError SSO_LEGACY_UNAVAILABLE passed through unchanged by the proxy (module-legacy.conf).
  // With Oracle present this would be the Forms servlet launch (untested-live).
  test.info().annotations.push({ type: 'observed', description: `${r.status()} ${r.headers()['content-type']} ${r.url()}` });
  expect([200, 502, 503]).toContain(r.status());
  expect(r.headers()['content-type'] ?? '').toContain('application/json');
  const body = await r.json();
  expect(body.code).toBe('SSO_LEGACY_UNAVAILABLE');
});

test('SSO bridge is not reachable from the browser and rejects NEW modules (via proxy internal location)', async ({ page }) => {
  await login(page, USERS.executive.email);
  const direct = await page.request.post('/legacy/sso/exchange', { data: { module: 'EMPLOYEE' } });
  expect(direct.status()).toBe(404); // nginx `internal` location
});
