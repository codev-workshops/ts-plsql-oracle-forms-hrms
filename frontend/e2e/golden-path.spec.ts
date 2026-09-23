import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * Phase 0 golden path (CUTOVER_PLAN.md §4 acceptance): login → home tiles → change password →
 * logout. By default it runs against the msw browser worker (VITE_MOCK_API=true); with
 * `E2E_REAL_STACK=1` (see playwright.config.ts) the same spec runs against the real
 * frontend + auth-service + PostgreSQL stack. Accounts come from
 * e2e/seed-accounts.ts == tools/fixtures/pg/04_user_accounts.sql.
 */

const ROTATED_PASSWORD = 'Stronger9!';
const REAL_STACK = process.env.E2E_REAL_STACK === '1';

/** Click Save and wait for this submission's own `PUT /api/auth/password` 204. */
async function saveAndAwaitPasswordChange(page: Page): Promise<void> {
  const changed = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith('/api/auth/password') && r.status() === 204);
  await page.getByRole('button', { name: 'Save' }).click();
  await changed;
}

async function dismissAllToasts(page: Page): Promise<void> {
  const toasts = page.getByRole('region', { name: 'Notifications' }).getByRole('button', { name: 'Dismiss' });
  while ((await toasts.count()) > 0) await toasts.first().click();
  await expect(page.getByText('Password changed')).toHaveCount(0);
}

/**
 * Real stack only: make sure the executive account is back on the seed password.
 * At most 4 requests; never puts credential values into error text.
 */
async function restoreSeedPassword(request: APIRequestContext): Promise<void> {
  const account = SEED_ACCOUNTS.executive.email;
  const rotatedLogin = await request.post('/api/auth/login', { data: { username: account, password: ROTATED_PASSWORD } });
  if (rotatedLogin.ok()) {
    const { accessToken } = (await rotatedLogin.json()) as { accessToken: string };
    const headers = { Authorization: `Bearer ${accessToken}` };
    const restored = await request.put('/api/auth/password', { headers, data: { currentPassword: ROTATED_PASSWORD, newPassword: SEED_PASSWORD } });
    await request.post('/api/auth/logout', { headers });
    if (restored.status() !== 204) throw new Error(`seed password restore for ${account} failed: PUT /api/auth/password ${restored.status()}`);
    return;
  }
  const seedLogin = await request.post('/api/auth/login', { data: { username: account, password: SEED_PASSWORD } });
  if (!seedLogin.ok()) {
    throw new Error(`seed password for ${account} not restored: login with rotated password ${rotatedLogin.status()}, with seed password ${seedLogin.status()}`);
  }
  const { accessToken } = (await seedLogin.json()) as { accessToken: string };
  await request.post('/api/auth/logout', { headers: { Authorization: `Bearer ${accessToken}` } });
}

test.describe('P0 golden path', () => {
  test('login, authority-filtered tiles, legacy tiles disabled (P0-D1), logout', async ({ page }) => {
    await page.goto('/employees');
    await expect(page).toHaveURL(/\/login$/);

    await page.getByLabel('E-mail').fill(SEED_ACCOUNTS.staff.email);
    await page.getByLabel('Password').fill(SEED_PASSWORD);
    await page.getByLabel('Password').press('Enter');

    await expect(page.getByRole('heading', { name: 'Welcome, DAVID MARTINEZ' })).toBeVisible();
    const employees = page.getByTestId('tile-employees');
    await expect(employees).toHaveAttribute('data-legacy', 'true');
    await expect(employees).toHaveAttribute('aria-disabled', 'true');
    await expect(employees).not.toHaveAttribute('href', /.*/);
    await expect(employees).toContainText('Not available in this environment');
    await employees.click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('tile-payroll')).toHaveCount(0);

    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('manager sees payroll tile', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(SEED_ACCOUNTS.manager.email);
    await page.getByLabel('Password').fill(SEED_PASSWORD);
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByTestId('tile-payroll')).toHaveCount(1);
    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });

  // P0-D1 (golden-oracle mode OFF): legacy Oracle Forms are not run, so the legacy-tile
  // navigation through the proxy SSO bridge (/employees -> /legacy/sso/exchange -> Forms)
  // is out of scope for this phase and excluded from the P0 gate. Re-enable when a
  // legacy-tile token carrier is designed and a Forms runtime is available.
  test.skip('legacy tile navigates through the proxy SSO exchange into Oracle Forms (P0-D1: out of scope)', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(SEED_ACCOUNTS.staff.email);
    await page.getByLabel('Password').fill(SEED_PASSWORD);
    await page.getByLabel('Password').press('Enter');
    await page.getByTestId('tile-employees').click();
    await expect(page).toHaveURL(/\/employees$/);
  });

  // Real stack only: the msw worker's session store lives in page memory and is wiped by reload.
  test('reload restores the session with exactly one POST /api/auth/refresh', async ({ page }) => {
    test.skip(!REAL_STACK, 'msw session store does not survive a reload');
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(SEED_ACCOUNTS.staff.email);
    await page.getByLabel('Password').fill(SEED_PASSWORD);
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByRole('heading', { name: 'Welcome, DAVID MARTINEZ' })).toBeVisible();

    const refreshes: number[] = [];
    page.on('response', (r) => {
      if (r.request().method() === 'POST' && r.url().endsWith('/api/auth/refresh')) refreshes.push(r.status());
    });
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Welcome, DAVID MARTINEZ' })).toBeVisible();
    await expect(page.getByRole('status')).toHaveCount(0);
    await expect.poll(() => refreshes).toEqual([200]);

    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('invalid credentials show the uniform -20301 message', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(SEED_ACCOUNTS.staff.email);
    await page.getByLabel('Password').fill('wrong');
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid username or password');
  });

  test('change password enforces the -20310/-20311/-20312 policy then succeeds', async ({ page, request }) => {
    try {
      await runChangePasswordScenario(page);
    } catch (err) {
      if (REAL_STACK) {
        await restoreSeedPassword(request).catch((restoreErr: Error) => {
          throw new Error(`${restoreErr.message}\ncaused while handling: ${(err as Error).message}`, { cause: err });
        });
      }
      throw err;
    }
  });

  async function runChangePasswordScenario(page: Page): Promise<void> {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(SEED_ACCOUNTS.executive.email);
    await page.getByLabel('Password').fill(SEED_PASSWORD);
    await page.getByRole('button', { name: 'Login' }).click();
    await page.getByRole('link', { name: 'Change password' }).click();

    await page.getByLabel('Current password').fill(SEED_PASSWORD);
    await page.getByLabel('New password', { exact: true }).fill('lowercase1');
    await page.getByLabel('Confirm new password').fill('lowercase1');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('alert')).toHaveText('Password must contain an uppercase letter');

    await page.getByLabel('New password', { exact: true }).fill(ROTATED_PASSWORD);
    await page.getByLabel('Confirm new password').fill(ROTATED_PASSWORD);
    await saveAndAwaitPasswordChange(page);
    await expect(page.getByText('Password changed')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Welcome, JAMES RICHARDSON' })).toBeVisible();
    await dismissAllToasts(page);

    // Restore the committed seed password so later real-stack specs that log in as the
    // executive (seed-accounts.ts == tools/fixtures/pg/04_user_accounts.sql) keep working.
    await page.getByRole('link', { name: 'Change password' }).click();
    await page.getByLabel('Current password').fill(ROTATED_PASSWORD);
    await page.getByLabel('New password', { exact: true }).fill(SEED_PASSWORD);
    await page.getByLabel('Confirm new password').fill(SEED_PASSWORD);
    await saveAndAwaitPasswordChange(page);
    await expect(page.getByText('Password changed')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Welcome, JAMES RICHARDSON' })).toBeVisible();
    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);

    await page.getByLabel('E-mail').fill(SEED_ACCOUNTS.executive.email);
    await page.getByLabel('Password').fill(SEED_PASSWORD);
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByRole('heading', { name: 'Welcome, JAMES RICHARDSON' })).toBeVisible();
  }

  test('first-login user is forced onto the set-password page', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(SEED_ACCOUNTS.firstLogin.email);
    await page.getByLabel('Password').fill(SEED_PASSWORD);
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page).toHaveURL(/\/password$/);
    await expect(page.getByRole('heading', { name: 'Set your password' })).toBeVisible();
  });
});
