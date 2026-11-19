import { expect, test } from '@playwright/test';
import { EMPLOYEE, login, requireRealStack } from './helpers';

const ADMIN = process.env.E2E_ADMIN ?? 'james.richardson@company.com';

test('admin runs payroll, downloads the register, and the employee opens their payslip', async ({ page }) => {
  requireRealStack();
  await login(page, ADMIN);
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Payroll' }).click();
  const year = new Date().getFullYear();
  await page.getByRole('button', { name: `Generate ${year} periods` }).click();
  await expect(page.getByRole('status')).toHaveText(`12 monthly periods available for ${year}.`);
  const january = `${year}-01 (Jan)`;
  await expect(page.getByRole('rowheader', { name: january })).toBeVisible();
  await page.getByLabel('Run period').selectOption({ label: january });
  await page.getByRole('button', { name: 'Create run' }).click();

  const runHeading = page.getByRole('heading', { name: /Payroll run #\d+/ });
  await expect(runHeading).toBeVisible();
  const runId = (await runHeading.innerText()).split('#')[1];
  await expect(page.getByText('PENDING', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Calculate payroll' }).click();
  await expect(page.getByRole('heading', { name: 'Payroll details' })).toBeVisible();
  await page.getByLabel('Filter by employee').selectOption('22');
  await expect(page.getByRole('rowheader', { name: 'BASE_PAY' })).toBeVisible();
  await page.getByRole('button', { name: 'Approve payroll' }).click();
  await expect(page.getByText('APPROVED', { exact: true })).toBeVisible();

  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download pay register' }).click();
  expect((await downloaded).suggestedFilename()).toBe(`PAY_REGISTER_${runId}.csv`);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await login(page, EMPLOYEE);
  await page.goto('/payroll/payslips');
  await expect(page.getByRole('heading', { name: 'My payslip' })).toBeVisible();
  await page.getByLabel('Run ID').fill(runId);
  await page.getByRole('button', { name: 'Find payslip' }).click();
  await expect(page.getByRole('heading', { name: 'THOMAS BAKER' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Year to date' })).toBeVisible();
});
