import { expect, test, type Page } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

test.describe('P1 performance golden path', () => {
  test.skip(!process.env.VITE_MODULE_FLAGS?.includes('performance='), 'requires performance module promotion');

  async function login(page: Page, email: string) {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Password').fill(SEED_PASSWORD);
    await page.getByRole('button', { name: 'Login' }).click();
  }

  test('staff submits self-assessment, manager completes review, executive opens cycle', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.staff.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await page.getByRole('tab', { name: 'My Reviews' }).click();
    await page.getByRole('button', { name: 'Open' }).first().click();
    await page.getByLabel('Self-assessment').fill('I delivered Phase 1 improvements.');
    await page.getByRole('button', { name: 'Submit' }).click();
    await expect(page.locator('[data-status="MANAGER_REVIEW"]').first()).toBeVisible();
    await page.getByRole('button', { name: 'Logout' }).click();

    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await page.getByRole('tab', { name: 'Team' }).click();
    await page.getByLabel('Cycle').selectOption({ label: 'FY2025 Annual Review' });
    await page.getByRole('row', { name: /EMILY JOHNSON/ }).getByRole('button', { name: 'Review' }).click();
    await page.getByLabel('Overall rating').fill('4.6');
    await page.getByLabel('Manager assessment').fill('Excellent delivery.');
    await page.getByRole('button', { name: 'Submit' }).click();
    await expect(page.locator('[data-status="COMPLETED"]').first()).toBeVisible();
    await expect(page.getByText('Exceptional')).toBeVisible();
    await page.getByRole('button', { name: 'Logout' }).click();

    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await expect(page.getByRole('tab', { name: 'Review Cycles' })).toBeVisible();
    await page.getByRole('button', { name: 'Open cycle' }).last().click();
    await expect(page.locator('[data-status="OPEN"]').last()).toBeVisible();
  });
});
