import { expect, test } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * Phase 3 Employee golden path (COMPONENT_MAPPING.md §3, CUTOVER_PLAN.md §7). Here it runs
 * against the msw browser worker (`VITE_MOCK_API=true`, fixtures in src/mocks/employeeStore.ts);
 * the integration session runs the same spec against the real stack (`E2E_REAL_STACK=1`,
 * PostgreSQL-backed employee-service + salary-module).
 */

function iso(offset: number) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return d.toISOString().slice(0, 10);
}

async function login(page: import('@playwright/test').Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

const flag = process.env.VITE_MODULE_FLAGS?.match(/employee=(\w+)/)?.[1];

test.describe('P3 employee golden path', () => {
  test.skip(!flag, 'requires employee module promotion (VITE_MODULE_FLAGS=employee=NEW_READONLY|NEW)');
  test.skip(process.env.E2E_REAL_STACK === '1', 'mock-only here: relies on msw employee fixtures; the integration session owns the real-stack run');

  test('staff searches the grid and reads a record with all tabs', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.staff.email);
    await page.getByRole('link', { name: 'Employees' }).click();
    await expect(page.getByRole('table', { name: 'Employees' })).toBeVisible();
    await page.getByLabel('Name or number').fill('martinez');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByRole('link', { name: 'MARTINEZ, DAVID' }).click();

    await expect(page.getByRole('heading', { name: /MARTINEZ, DAVID/ })).toBeVisible();
    await expect(page.getByTestId('emp-number')).toHaveText('EMP-000011');
    await page.getByRole('tab', { name: 'History' }).click();
    await expect(page.getByRole('table', { name: 'Employment history' })).toBeVisible();
    await page.getByRole('tab', { name: 'Dependents' }).click();
    await expect(page.getByRole('table', { name: 'Dependents' })).toBeVisible();
    await page.getByRole('tab', { name: 'Contacts' }).click();
    await expect(page.getByRole('table', { name: 'Emergency contacts' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save' })).toHaveCount(0);
  });

  test('read-only at NEW_READONLY / write flows at NEW (create, salary change with grade-band warning, transfer, terminate)', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Employees' }).click();
    await expect(page.getByRole('table', { name: 'Employees' })).toBeVisible();

    if (flag !== 'NEW') {
      await expect(page.getByTestId('read-only-banner')).toBeVisible();
      await expect(page.getByRole('button', { name: 'New employee' })).toHaveCount(0);
      return;
    }

    await page.getByRole('button', { name: 'New employee' }).click();
    await page.getByLabel('First name *').fill('Grace');
    await page.getByLabel('Last name *').fill('Hopper');
    await page.getByLabel('Hire date *').fill(iso(7));
    await page.getByLabel('Department *').selectOption({ label: 'FIN – Finance' });
    await page.getByLabel('Job title *').selectOption({ label: 'Analyst (G3)' });
    await page.getByLabel('Initial salary').fill('50000');
    await page.getByRole('button', { name: 'Create employee' }).click();
    await expect(page.getByText(/Employee EMP-\d+ created/)).toBeVisible();
    await expect(page.getByRole('heading', { name: /Hopper, Grace/ })).toBeVisible();

    await page.getByRole('button', { name: 'Change salary' }).click();
    const salary = page.getByRole('dialog', { name: 'Change salary' });
    await salary.getByLabel('Effective date *').fill(iso(7));
    await salary.getByLabel('Base salary *').fill('80000');
    await expect(salary.getByTestId('grade-band-warning')).toContainText('outside the G3 grade band');
    await salary.getByLabel('Change reason *').fill('MARKET');
    await salary.getByRole('button', { name: 'Save salary' }).click();
    await expect(page.getByText('Salary set to 80,000.00 USD')).toBeVisible();

    await page.getByRole('button', { name: 'Transfer' }).click();
    const transfer = page.getByRole('dialog', { name: 'Transfer employee' });
    await transfer.getByLabel('Effective date *').fill(iso(7));
    await transfer.getByLabel('New department *').selectOption({ label: 'ENG – Engineering' });
    await transfer.getByRole('button', { name: 'Confirm transfer' }).click();
    await expect(page.getByText(/transferred to Engineering/)).toBeVisible();

    await page.getByRole('button', { name: 'Terminate' }).click();
    const terminate = page.getByRole('dialog', { name: 'Terminate employee' });
    await terminate.getByLabel('Effective date *').fill(iso(14));
    await terminate.getByLabel('Reason *').fill('RESIGNED');
    await terminate.getByRole('button', { name: 'Confirm termination' }).click();
    await expect(page.getByText(/terminated$/)).toBeVisible();
    await expect(page.getByTestId('emp-status')).toHaveText('Terminated');
    await expect(page.getByRole('button', { name: 'Terminate' })).toHaveCount(0);
  });
});
