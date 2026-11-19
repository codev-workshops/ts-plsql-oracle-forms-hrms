import { expect, test } from '@playwright/test';
import { EMPLOYEE, login, requireRealStack } from './helpers';

test.describe('login', () => {
  requireRealStack();

  test('rejects a wrong password', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Email').fill(EMPLOYEE);
    await page.getByLabel('Password').fill('wrong-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid username or password');
  });

  test('signs in, shows navigation and signs out', async ({ page }) => {
    await login(page, EMPLOYEE);
    const nav = page.getByRole('navigation', { name: 'Main' });
    for (const label of ['Leave', 'Employees', 'Performance']) {
      await expect(nav.getByRole('link', { name: label })).toBeVisible();
    }
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });
});
