import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * P4 payroll golden path against the REAL stack (Vite -> auth-service -> PostgreSQL), no msw.
 * Run by the integration session only:
 *   E2E_REAL_STACK=1 VITE_MODULE_FLAGS=payroll=NEW,payroll.engine=JAVA npx playwright test payroll-real-stack
 * Precondition: pristine seed (tools/fixtures/pg/*.sql) + tools/parallel-run/fixtures/payroll.sql,
 * backend started with HRMS_FLAG_PAYROLL=NEW HRMS_FLAG_PAYROLL_ENGINE=JAVA, period 202406 OPEN with
 * no run yet. Expected figures come from tests/golden/payroll/202406.json (recorded PKG_PAYROLL).
 */
test.skip(process.env.E2E_REAL_STACK !== '1', 'real-stack only');

async function login(page: import('@playwright/test').Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

test('HR opens JUN-2024, creates + calculates a run, reviews details, approves, reads payslip YTD, downloads register', async ({ page }) => {
  test.setTimeout(120_000);
  await login(page, SEED_ACCOUNTS.executive.email);

  // period list (HR opens period 202406 – seeded OPEN)
  await page.getByRole('link', { name: 'Payroll' }).click();
  await expect(page.getByRole('table', { name: 'Pay periods' })).toBeVisible();
  await expect(page.getByTestId('period-row-202406')).toContainText(/Open|Processing/);
  await page.getByTestId('period-runs-202406').click();
  await expect(page.getByRole('table', { name: 'Payroll runs' })).toBeVisible();
  await expect(page.getByTestId('runs-period-name')).toHaveText('JUN-2024');

  // create run
  await page.getByTestId('create-run').click();
  const dialog = page.getByRole('dialog', { name: 'Create payroll run' });
  await dialog.getByLabel(/Run type/).selectOption('REGULAR');
  await dialog.getByRole('button', { name: 'Create run' }).click();
  await expect(dialog).toHaveCount(0);
  const pending = page.locator('[data-testid^="run-row-"]').filter({ hasText: 'Pending' });
  await expect(pending).toHaveCount(1);
  const runId = Number((await pending.getAttribute('data-testid'))!.replace('run-row-', ''));
  const row = page.getByTestId(`run-row-${runId}`);

  // calculate (202 + /status polling -> progress -> Calculated)
  await page.getByTestId(`run-calculate-${runId}`).click();
  await expect(page.getByTestId(`run-progress-${runId}`)).toBeVisible();
  await expect(row.locator('.badge').first()).toHaveText('Calculated', { timeout: 60_000 });
  await expect(page.getByTestId(`run-errors-${runId}`)).toHaveCount(0);
  await expect(row).toContainText('23'); // employeeCount
  await expect(row).toContainText('300,833.32');
  await expect(row).toContainText('82,259.64');
  await expect(row).toContainText('218,573.68');

  // pay details – emp 2 (SARAH CHEN): BASE_PAY +, FED/FICA/MEDICARE negative (TX: no STATE_TAX row by contract)
  await page.getByTestId(`run-details-${runId}`).click();
  await expect(page.getByRole('table', { name: 'Pay details' })).toBeVisible();
  await page.getByLabel('Employee id').fill('2');
  await page.getByRole('button', { name: 'Apply' }).click();
  const details = page.getByRole('table', { name: 'Pay details' });
  await expect(details.getByRole('row').filter({ hasText: 'EMP-' })).toHaveCount(4);
  await expect(details.getByRole('row').filter({ hasText: 'EMP-000002' })).toHaveCount(4);
  await expect(details.getByRole('row').filter({ hasText: 'BASE_PAY' })).toContainText('31,666.67');
  await expect(details.getByRole('row').filter({ hasText: 'FED_TAX' })).toContainText('-8,188.73');
  await expect(details.getByRole('row').filter({ hasText: 'FICA' })).toContainText('-1,963.33');
  await expect(details.getByRole('row').filter({ hasText: 'MEDICARE' })).toContainText('-459.17');
  await expect(details.getByRole('row').filter({ hasText: 'STATE_TAX' })).toHaveCount(0);
  await expect(page.getByTestId('detail-error-banner')).toHaveCount(0);

  // approve
  await page.getByRole('link', { name: '← Pay periods' }).click();
  await page.getByTestId('period-runs-202406').click();
  await page.getByTestId(`run-approve-${runId}`).click();
  await page.getByTestId('confirm-approve').click();
  const result = page.getByTestId('approval-result');
  await expect(result).toContainText(`Run #${runId} approved`);
  await result.getByRole('button', { name: 'Close' }).click();
  await expect(row.locator('.badge').first()).toHaveText('Approved');

  // payslip YTD (approved MAY run 9001 20833.33 + JUN 31666.67 = 52500.00)
  await page.goto(`/payroll/runs/${runId}/payslips/2`);
  const payslip = page.getByTestId('payslip');
  await expect(payslip).toContainText('SARAH CHEN');
  await expect(page.getByTestId('payslip-gross')).toHaveText('31,666.67');
  await expect(payslip.getByRole('row').filter({ hasText: 'Gross pay' })).toContainText('52,500.00');
  await expect(payslip.getByRole('row').filter({ hasText: 'Federal tax' })).toContainText('8,188.73');
  await expect(payslip.getByRole('row').filter({ hasText: 'State tax' })).toContainText('0.00');
  await expect(page.getByTestId('payslip-net')).toHaveText('21,055.44');

  // register.csv with masked bank columns
  await page.goto('/payroll/periods/202406/runs');
  await expect(page.getByTestId(`run-register-${runId}`)).toBeVisible();
  await page.getByTestId(`run-row-${runId}`).getByLabel('include bank (masked)').check();
  const download = page.waitForEvent('download');
  await page.getByTestId(`run-register-${runId}`).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(new RegExp(`^PAY_REGISTER_${runId}_\\d{8}_\\d{6}\\.csv$`));
  const path = await file.path();
  const csv = readFileSync(path!, 'utf8');
  const lines = csv.trim().split(/\r?\n/);
  expect(lines[0]).toContain('BANK_NAME,ROUTING_LAST4,ACCOUNT_LAST4');
  expect(lines.length).toBe(24);
  expect(csv).not.toMatch(/\b\d{9}\b/); // no full routing numbers
  expect(csv).not.toMatch(/ROUTING_NUMBER|ACCOUNT_NUMBER/);
});

test('manager (PAYROLL:VIEW) cannot create/approve/calculate', async ({ page }) => {
  await login(page, SEED_ACCOUNTS.manager.email);
  await page.getByRole('link', { name: 'Payroll' }).click();
  await page.getByTestId('period-runs-202406').click();
  await expect(page.getByRole('table', { name: 'Payroll runs' })).toBeVisible();
  await expect(page.getByTestId('create-run')).toHaveCount(0);
  await expect(page.locator('[data-testid^="run-approve-"]')).toHaveCount(0);
  await expect(page.locator('[data-testid^="run-calculate-"]')).toHaveCount(0);
});
