import { expect, test } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * Phase 4 Payroll golden path (COMPONENT_MAPPING.md §4, CUTOVER_PLAN.md §8): period → create run →
 * calculate (202 + /status polling) → details with ERROR rows → payslip → approve → register CSV.
 * Here it is a smoke run against the msw browser worker (`VITE_MOCK_API=true`, fixtures in
 * src/mocks/payrollStore.ts) with `VITE_MODULE_FLAGS=payroll=NEW,payroll.engine=JAVA`; the
 * integration session runs the real-stack version after the shadow gate promotes the engine.
 */

async function login(page: import('@playwright/test').Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

const flag = process.env.VITE_MODULE_FLAGS?.match(/(?:^|,)payroll=(\w+)/)?.[1];

test.describe('P4 payroll tile gating', () => {
  test.skip(process.env.E2E_REAL_STACK === '1', 'mock-only here: relies on msw payroll fixtures; the integration session owns the real-stack run');
  test.skip(flag === 'NEW', 'covered by the golden path below when payroll=NEW');

  test('payroll tile routes to Forms (disabled legacy tile) until payroll=NEW', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.manager.email);
    const tile = page.getByTestId('tile-payroll');
    await expect(tile).toHaveAttribute('data-legacy', 'true');
    await expect(tile).not.toHaveAttribute('href', /.*/);
    await page.goto('/payroll');
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('tablist', { name: 'Payroll' })).toHaveCount(0);
  });
});

test.describe('P4 payroll golden path', () => {
  test.skip(flag !== 'NEW', 'requires payroll promotion (VITE_MODULE_FLAGS=payroll=NEW,payroll.engine=JAVA)');
  test.skip(process.env.E2E_REAL_STACK === '1', 'mock-only here: relies on msw payroll fixtures; the integration session owns the real-stack run');

  test('manager (PAYROLL:VIEW) browses periods, runs, details and a payslip read-only', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Payroll' }).click();
    await expect(page.getByRole('table', { name: 'Pay periods' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Close period' })).toHaveCount(0);

    await page.getByTestId('period-runs-202402').click();
    await expect(page.getByRole('table', { name: 'Payroll runs' })).toBeVisible();
    await expect(page.getByTestId('run-errors-1002')).toHaveText('1 error');
    await expect(page.getByTestId('create-run')).toHaveCount(0);
    await expect(page.getByTestId('run-approve-1002')).toHaveCount(0);

    await page.getByTestId('run-details-1002').click();
    await expect(page.getByRole('table', { name: 'Pay details' })).toBeVisible();
    await expect(page.getByTestId('detail-error-banner')).toContainText('1 employee');
    await expect(page.getByText('-20104: No active salary record for employee 22')).toBeVisible();

    await page.getByTestId('payslip-link-21').click();
    await expect(page.getByTestId('payslip')).toContainText('JENNIFER PARK');
    await expect(page.getByTestId('payslip-net')).toHaveText('5,684.00');
  });

  test('executive (PAYROLL:APPROVE) creates, calculates, approves and downloads the register', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Payroll' }).click();
    await expect(page.getByRole('table', { name: 'Pay periods' })).toBeVisible();
    await page.getByTestId('period-runs-202403').click();
    await expect(page.getByRole('table', { name: 'Payroll runs' })).toBeVisible();

    await page.getByTestId('create-run').click();
    const dialog = page.getByRole('dialog', { name: 'Create payroll run' });
    await dialog.getByLabel(/Run type/).selectOption('');
    await dialog.getByRole('button', { name: 'Create run' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('runType is required');
    await dialog.getByLabel(/Run type/).selectOption('REGULAR');
    await dialog.getByRole('button', { name: 'Create run' }).click();
    await expect(dialog).toHaveCount(0);

    const row = page.getByTestId('run-row-1003');
    await expect(row).toContainText('Pending');
    await row.getByRole('button', { name: 'Calculate' }).click();
    await expect(page.getByTestId('run-progress-1003')).toBeVisible();
    await expect(row.locator('.badge').first()).toHaveText('Calculated', { timeout: 10_000 });
    await expect(page.getByTestId('run-errors-1003')).toHaveText('1 error');

    await page.getByTestId('run-approve-1003').click();
    await page.getByTestId('confirm-approve').click();
    const result = page.getByTestId('approval-result');
    await expect(result).toContainText('Run #1003 approved');
    await expect(result).toContainText('EMP-000022');
    await result.getByRole('button', { name: 'Close' }).click();
    await expect(row.locator('.badge').first()).toHaveText('Approved');

    const download = page.waitForEvent('download');
    await page.getByTestId('run-register-1003').click();
    expect((await download).suggestedFilename()).toBe('PAY_REGISTER_1003_2024_03_Monthly.csv');
  });
});
