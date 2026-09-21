import { expect, test } from '@playwright/test';

// Integration-harness copy of e2e/golden-path.spec.ts with the committed seed accounts
// (tools/fixtures/pg/04_user_accounts.sql) substituted for the msw-only *.hrms.example users.
// NOT part of the repo; used to separate the fixture mismatch from UI behaviour.

const PWD = 'Welcome1!';

test.describe('P0 golden path (seed-account adapted)', () => {
  test('login, authority-filtered tiles, legacy tiles disabled (P0-D1), logout', async ({ page }) => {
    await page.goto('/employees');
    await expect(page).toHaveURL(/\/login$/);

    await page.getByLabel('E-mail').fill('david.martinez@company.com');
    await page.getByLabel('Password').fill(PWD);
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
    await page.getByLabel('E-mail').fill('jennifer.park@company.com');
    await page.getByLabel('Password').fill(PWD);
    await page.getByLabel('Password').press('Enter');
    await expect(page.getByRole('heading', { name: 'Welcome, JENNIFER PARK' })).toBeVisible();
    await expect(page.getByTestId('tile-payroll')).toHaveCount(1);
    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('invalid credentials show the uniform -20301 message', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill('david.martinez@company.com');
    await page.getByLabel('Password').fill('wrong');
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid username or password');
  });

  test('change password enforces the -20310/-20311/-20312 policy then succeeds', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill('james.richardson@company.com');
    await page.getByLabel('Password').fill(PWD);
    await page.getByRole('button', { name: 'Login' }).click();
    await page.getByRole('link', { name: 'Change password' }).click();

    await page.getByLabel('Current password').fill(PWD);
    await page.getByLabel('New password', { exact: true }).fill('lowercase1');
    await page.getByLabel('Confirm new password').fill('lowercase1');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('alert')).toHaveText('Password must contain an uppercase letter');

    await page.getByLabel('New password', { exact: true }).fill('Stronger9!');
    await page.getByLabel('Confirm new password').fill('Stronger9!');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Password changed')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Welcome, JAMES RICHARDSON' })).toBeVisible();
  });

  test('first-login user is forced onto the set-password page', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill('emily.johnson@company.com');
    await page.getByLabel('Password').fill(PWD);
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page).toHaveURL(/\/password$/);
    await expect(page.getByRole('heading', { name: 'Set your password' })).toBeVisible();
  });

  test('401 on expired access token triggers refresh and the page still loads', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill('david.martinez@company.com');
    await page.getByLabel('Password').fill(PWD);
    await page.getByLabel('Password').press('Enter');
    await expect(page.getByRole('heading', { name: 'Welcome, DAVID MARTINEZ' })).toBeVisible();
    // Reload: in-memory access token is gone; the app must recover via the refresh cookie.
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Welcome, DAVID MARTINEZ' })).toBeVisible();
  });
});
