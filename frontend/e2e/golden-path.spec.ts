import { expect, test } from '@playwright/test';

/**
 * Phase 0 golden path (CUTOVER_PLAN.md §4 acceptance): login → home tiles → change password →
 * logout. By default it runs against the msw browser worker (VITE_MOCK_API=true); with
 * `E2E_REAL_STACK=1` (see playwright.config.ts) the same spec runs against the real
 * frontend + auth-service + PostgreSQL stack.
 */

test.describe('P0 golden path', () => {
  test('login, authority-filtered tiles, legacy tiles disabled (P0-D1), logout', async ({ page }) => {
    await page.goto('/employees');
    await expect(page).toHaveURL(/\/login$/);

    await page.getByLabel('E-mail').fill('staff@hrms.example');
    await page.getByLabel('Password').fill('Welcome1');
    await page.getByLabel('Password').press('Enter');

    await expect(page.getByRole('heading', { name: 'Welcome, Sam Staff' })).toBeVisible();
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

  // P0-D1 (golden-oracle mode OFF): legacy Oracle Forms are not run, so the legacy-tile
  // navigation through the proxy SSO bridge (/employees -> /legacy/sso/exchange -> Forms)
  // is out of scope for this phase and excluded from the P0 gate. Re-enable when a
  // legacy-tile token carrier is designed and a Forms runtime is available.
  test.skip('legacy tile navigates through the proxy SSO exchange into Oracle Forms (P0-D1: out of scope)', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill('staff@hrms.example');
    await page.getByLabel('Password').fill('Welcome1');
    await page.getByLabel('Password').press('Enter');
    await page.getByTestId('tile-employees').click();
    await expect(page).toHaveURL(/\/employees$/);
  });

  test('invalid credentials show the uniform -20301 message', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill('staff@hrms.example');
    await page.getByLabel('Password').fill('wrong');
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid username or password');
  });

  test('change password enforces the -20310/-20311/-20312 policy then succeeds', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill('admin@hrms.example');
    await page.getByLabel('Password').fill('Welcome1');
    await page.getByRole('button', { name: 'Login' }).click();
    await page.getByRole('link', { name: 'Change password' }).click();

    await page.getByLabel('Current password').fill('Welcome1');
    await page.getByLabel('New password', { exact: true }).fill('lowercase1');
    await page.getByLabel('Confirm new password').fill('lowercase1');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('alert')).toHaveText('Password must contain an uppercase letter');

    await page.getByLabel('New password', { exact: true }).fill('Stronger9');
    await page.getByLabel('Confirm new password').fill('Stronger9');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Password changed')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Welcome, Ada Admin' })).toBeVisible();
  });

  test('first-login user is forced onto the set-password page', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill('newhire@hrms.example');
    await page.getByLabel('Password').fill('Welcome1');
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page).toHaveURL(/\/password$/);
    await expect(page.getByRole('heading', { name: 'Set your password' })).toBeVisible();
  });
});
