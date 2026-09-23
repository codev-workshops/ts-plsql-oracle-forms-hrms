import { expect, test, type Page } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

const realStack = process.env.E2E_REAL_STACK === '1';
const flags = process.env.VITE_MODULE_FLAGS ?? '';

async function login(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
}

/**
 * MOCK-ONLY smoke of the P5 reporting + HRMS_ADMIN rebuild against the msw browser worker
 * (src/mocks/p5Handlers.ts). The real-stack run (reporting-service + PostgreSQL, tests/golden/
 * views-baseline.csv reconciliation) is the integration session's job.
 */
test.describe('P5 reporting + admin golden path (mock stack)', () => {
  test.skip(!flags.includes('reporting=NEW'), 'requires reporting=NEW');
  test.skip(realStack, 'mock-only: relies on msw P5 fixtures');

  test('manager runs the employee directory, filters it and exports CSV', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Reports' }).click();
    const table = page.getByRole('table', { name: 'Employee Directory' });
    await expect(table).toBeVisible();
    const before = await table.getByRole('row').count();
    expect(before).toBeGreaterThan(2);
    await page.getByLabel('Department').selectOption('2');
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(table.getByRole('row').filter({ hasText: 'Finance' })).toHaveCount(1);
    await expect(table.getByRole('row')).toHaveCount(2);
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export CSV' }).click();
    expect((await download).suggestedFilename()).toMatch(/^employee-directory.*\.csv$/);

    await page.getByRole('tab', { name: 'Compensation' }).click();
    await expect(page.getByRole('table', { name: 'Employee Compensation' })).toBeVisible();
    await expect(page.getByTestId('compensation-summary')).toBeVisible();
    await page.getByRole('tab', { name: 'Leave Summary' }).click();
    await expect(page.getByRole('table', { name: 'Leave Summary' })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);

    await page.getByRole('tab', { name: 'Pending Approvals' }).click();
    await expect(page.getByRole('table', { name: 'Pending Approvals' })).toBeVisible();
  });

  test('staff without REPORTS:VIEW gets neither the Reports nor the Administration tile', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.staff.email);
    await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
    await expect(page.getByTestId('tile-reports')).toHaveCount(0);
    await expect(page.getByTestId('tile-admin')).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: 'Modules' }).getByRole('link', { name: 'Reports' })).toHaveCount(0);
  });

  test('executive creates a department, deactivates it and searches the audit log', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Administration' }).click();
    await expect(page.getByRole('table', { name: 'Departments' })).toBeVisible();
    await page.getByRole('button', { name: 'New department' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/^Code/).fill('E2E');
    await dialog.getByLabel(/^Name/).fill('E2E Department');
    await dialog.getByRole('button', { name: 'Create' }).click();
    const row = page.getByRole('row', { name: /E2E Department/ });
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: /^Deactivate/ }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Deactivate' }).click();
    await expect(row).toBeHidden();

    await page.getByRole('tab', { name: 'Audit log' }).click();
    await page.getByLabel(/^Table/).fill('DEPARTMENTS');
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByRole('table', { name: 'Audit log' })).toBeVisible();
  });
});

/** Forms decommission switch – only when every module flag is NEW and decommission=NEW. */
test.describe('P5 decommission switch (mock stack)', () => {
  test.skip(!flags.includes('decommission=NEW'), 'requires decommission=NEW');
  test.skip(realStack, 'mock-only');

  test('no legacy tiles or SSO bridge links remain in the shell', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
    await expect(page.locator('[data-legacy]')).toHaveCount(0);
    await expect(page.getByRole('banner')).toHaveAttribute('data-decommissioned', 'true');
  });
});
