import { expect, test, type Page } from '@playwright/test';
import { ROLE_AUTHORITIES, SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

// Ad-hoc integration checks (not committed): per-role tile set and the 401/refresh flow.
// Tile model mirrors src/app/modules.ts: employees/leave/performance for everyone,
// payroll needs PAYROLL:VIEW, reports/admin hidden until Phase 5.
function expectedTiles(role: keyof typeof ROLE_AUTHORITIES): string[] {
  const auths = new Set<string>(ROLE_AUTHORITIES[role]);
  const tiles = ['tile-employees', 'tile-leave', 'tile-performance'];
  if (auths.has('PAYROLL:VIEW')) tiles.push('tile-payroll');
  return tiles.sort();
}

async function login(page: Page, email: string, password = SEED_PASSWORD) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page.getByRole('heading', { name: /Welcome, / })).toBeVisible();
}

for (const key of ['staff', 'manager', 'executive'] as const) {
  test(`tiles match authorities for ${key}`, async ({ page }) => {
    const acct = SEED_ACCOUNTS[key];
    const pw = key === 'executive' ? process.env.EXEC_PASSWORD ?? SEED_PASSWORD : SEED_PASSWORD;
    await login(page, acct.email, pw);
    const tiles = await page
      .locator('[data-testid^="tile-"]')
      .evaluateAll((els) => els.map((e) => e.getAttribute('data-testid')));
    expect([...new Set(tiles)].sort()).toEqual(expectedTiles(acct.role));
    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
}

test('reload restores session via HttpOnly refresh cookie', async ({ page }) => {
  await login(page, SEED_ACCOUNTS.staff.email);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome, DAVID MARTINEZ' })).toBeVisible();
});

test('401 on an API call triggers one silent refresh and replays the request', async ({ page }) => {
  await login(page, SEED_ACCOUNTS.manager.email);
  const statuses: number[] = [];
  let refreshCalls = 0;
  page.on('response', (r) => {
    if (r.url().includes('/api/auth/password')) statuses.push(r.status());
    if (r.url().includes('/api/auth/refresh') && r.request().method() === 'POST') refreshCalls++;
  });
  let broken = false;
  await page.route('**/api/auth/password', async (route) => {
    const headers = { ...route.request().headers() };
    if (!broken) {
      broken = true;
      headers['authorization'] = 'Bearer garbage';
    }
    await route.continue({ headers });
  });
  await page.getByRole('link', { name: 'Change password' }).click();
  // Passes client-side zod policy; server rejects with PASSWORD_REUSED (400) on the replay.
  await page.getByLabel('Current password').fill(SEED_PASSWORD);
  await page.getByLabel('New password', { exact: true }).fill(SEED_PASSWORD);
  await page.getByLabel('Confirm new password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(/\/password$/);
  expect(statuses[0]).toBe(401);
  expect(statuses.length).toBe(2);
  expect(statuses[1]).toBe(400);
  expect(refreshCalls).toBe(1);
});

test('lost refresh cookie: reload lands on /login', async ({ page, context }) => {
  await login(page, SEED_ACCOUNTS.staff.email);
  await context.clearCookies();
  await page.reload();
  await expect(page).toHaveURL(/\/login/);
});
