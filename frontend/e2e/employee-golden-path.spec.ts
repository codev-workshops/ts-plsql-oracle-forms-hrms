import { expect, test } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * Phase 3 Employee golden path (COMPONENT_MAPPING.md §3, CUTOVER_PLAN.md §7). Here it is only
 * smoke-run against the msw browser worker (`VITE_MOCK_API=true`, fixtures in
 * src/mocks/employeeStore.ts); the integration session runs it against the real stack
 * (`E2E_REAL_STACK=1`, PostgreSQL-backed salary-module + employee-service) once both landed.
 *
 * Run: `VITE_MODULE_FLAGS=employee=NEW npm run e2e -- employee-golden-path`
 *  or  `VITE_MODULE_FLAGS=employee=NEW_READONLY ...` for the read-only cutover state.
 */

const FLAGS = process.env.VITE_MODULE_FLAGS ?? '';
const writable = FLAGS.includes('employee=NEW') && !FLAGS.includes('employee=NEW_READONLY');

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

/** In-app navigation: a full reload would reset the msw worker's in-memory session store. */
async function openEmployee(page: import('@playwright/test').Page, search: string, row: RegExp) {
  await page.getByRole('link', { name: 'Employees' }).click();
  await page.getByRole('searchbox', { name: 'Search' }).fill(search);
  await page.getByRole('button', { name: 'Search' }).click();
  await page.getByRole('row', { name: row }).click();
}

test.describe('P3 employee golden path', () => {
  test.skip(!FLAGS.includes('employee='), 'requires employee module promotion (VITE_MODULE_FLAGS=employee=NEW or employee=NEW_READONLY)');
  test.skip(process.env.E2E_REAL_STACK === '1', 'mock-only here: the integration session owns the real-stack run');

  test('HR searches the grid, opens an employee and reads every tab with the SSN masked', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Employees' }).click();
    await expect(page.getByRole('table', { name: 'Employees' })).toBeVisible();

    await page.getByRole('searchbox', { name: 'Search' }).fill('martinez');
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByTestId('page-info')).toContainText('1 employees');
    await page.getByRole('row', { name: /MARTINEZ, DAVID/ }).click();

    await expect(page.getByRole('heading', { name: /DAVID MARTINEZ/ })).toBeVisible();
    const details = page.getByLabel('Employee details');
    await expect(details).toContainText('•••-••-0011');
    await expect(details).not.toContainText('123-45-0011');

    await page.getByRole('tab', { name: 'History' }).click();
    await expect(page.getByRole('table', { name: 'Employment history' })).toContainText('HIRE');
    await page.getByRole('tab', { name: 'Salary' }).click();
    await expect(page.getByTestId('current-salary')).toContainText('$55,000.00');
    await page.getByRole('tab', { name: 'Dependents' }).click();
    await expect(page.getByRole('table', { name: 'Dependents' })).toContainText('•••-••-4321');
    await page.getByRole('tab', { name: 'Contacts' }).click();
    await expect(page.getByRole('table', { name: 'Emergency contacts' })).toContainText('LUCIA MARTINEZ');
  });

  test('write controls follow the proxy flag', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await openEmployee(page, 'martinez', /MARTINEZ, DAVID/);
    await expect(page.getByRole('heading', { name: /DAVID MARTINEZ/ })).toBeVisible();
    if (writable) {
      await expect(page.getByRole('button', { name: 'Edit' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Terminate' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Transfer' })).toBeVisible();
    } else {
      await expect(page.getByRole('note')).toContainText('read-only during cutover');
      await expect(page.getByRole('button', { name: 'Edit' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Terminate' })).toHaveCount(0);
    }
  });

  test('HR changes a salary and sees the grade-band warning, then transfers the employee', async ({ page }) => {
    test.skip(!writable, 'write flows render only at employee=NEW');
    await login(page, SEED_ACCOUNTS.executive.email);
    await openEmployee(page, 'johnson', /JOHNSON, EMILY/);
    await page.getByRole('tab', { name: 'Salary' }).click();
    await page.getByRole('button', { name: 'Change salary' }).click();
    const dialog = page.getByRole('dialog', { name: 'Change salary' });
    await dialog.getByLabel(/^Base salary/).fill('90000');
    await expect(dialog.getByTestId('grade-band-warning')).toContainText('outside the G3 grade band');
    await dialog.getByLabel(/^Change reason/).fill('PROMOTION');
    await dialog.getByRole('button', { name: 'Apply change' }).click();
    await expect(page.getByText('Salary changed to $90,000.00')).toBeVisible();
    await expect(page.getByTestId('out-of-band-badge')).toBeVisible();

    await page.getByRole('tab', { name: 'Details' }).click();
    await page.getByRole('button', { name: 'Transfer' }).click();
    const xfer = page.getByRole('dialog', { name: 'Transfer employee' });
    await xfer.getByLabel(/^Effective date/).fill(iso(0));
    await xfer.getByLabel(/^New department/).selectOption('3');
    await xfer.getByRole('button', { name: 'Transfer' }).click();
    await expect(page.getByText('Transferred to Engineering')).toBeVisible();
    await expect(page.getByRole('table', { name: 'Employment history' })).toContainText('TRANSFER');
  });

  test('a viewer never sees write controls or other employees’ salary', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.staff.email);
    await openEmployee(page, 'park', /PARK, JENNIFER/);
    await expect(page.getByRole('heading', { name: /JENNIFER PARK/ })).toBeVisible();
    await page.getByRole('tab', { name: 'Salary' }).click();
    await expect(page.getByRole('note').filter({ hasText: 'restricted' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Edit' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'New employee' })).toHaveCount(0);
  });
});
