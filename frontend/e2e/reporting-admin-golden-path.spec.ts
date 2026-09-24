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

  test('executive maintains holidays, pay elements and tax brackets (reserved rows, ladder gaps, overlap -20608)', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Administration' }).click();

    await page.getByRole('tab', { name: 'Holidays' }).click();
    await expect(page.getByRole('table', { name: 'Holidays' })).toBeVisible();
    await page.getByRole('button', { name: 'New holiday' }).click();
    const holiday = page.getByRole('dialog', { name: 'New holiday' });
    await holiday.getByLabel(/^Date/).fill('1980-01-01');
    await holiday.getByLabel(/^Name/).fill('Too early');
    await holiday.getByRole('button', { name: 'Create' }).click();
    await expect(holiday.getByRole('alert')).toContainText(/1990-01-01/);
    await holiday.getByLabel(/^Date/).fill('2025-07-04');
    await holiday.getByLabel(/^Name/).fill('Independence Day');
    await holiday.getByRole('button', { name: 'Create' }).click();
    const holidayRow = page.getByRole('row', { name: /Independence Day/ });
    await expect(holidayRow).toContainText('Company-wide');
    await holidayRow.getByRole('button', { name: /^Deactivate/ }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Deactivate' }).click();
    await expect(holidayRow).toBeHidden();

    await page.getByRole('tab', { name: 'Pay elements' }).click();
    await expect(page.getByRole('table', { name: 'Pay elements' })).toBeVisible();
    await expect(page.getByRole('row', { name: /BASE_PAY \(reserved\)/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Deactivate BASE_PAY/ })).toHaveCount(0);
    await page.getByRole('button', { name: /^Edit FED_TAX/ }).click();
    const element = page.getByRole('dialog');
    await element.getByLabel(/^Calculation/).selectOption('FLAT');
    await element.getByRole('button', { name: 'Save' }).click();
    await expect(element.getByRole('alert')).toContainText(/reserved/);
    await element.getByRole('button', { name: 'Cancel' }).click();

    await page.getByRole('tab', { name: 'Tax brackets' }).click();
    await expect(page.getByRole('table', { name: 'Tax brackets' })).toBeVisible();
    await page.getByLabel('Tax year').fill('2024');
    await expect(page.getByTestId('ladder-gaps')).toContainText('[50000.00, 60000.00)');
    await page.getByRole('button', { name: 'New tax bracket' }).click();
    const bracket = page.getByRole('dialog', { name: 'New tax bracket' });
    await bracket.getByLabel(/^Tax year/).fill('2024');
    await bracket.getByLabel(/^Filing status/).selectOption('SINGLE');
    await bracket.getByLabel(/^Bracket minimum/).fill('45000');
    await bracket.getByLabel(/^Bracket maximum/).fill('60000');
    await bracket.getByLabel(/^Rate/).fill('0.22');
    await bracket.getByRole('button', { name: 'Create' }).click();
    await expect(bracket.getByRole('alert')).toContainText(/overlaps/);
    await bracket.getByLabel(/^Bracket minimum/).fill('50000');
    await bracket.getByRole('button', { name: 'Create' }).click();
    await expect(bracket).toBeHidden();
    await expect(page.getByTestId('ladder-gaps')).not.toContainText('50000.00');
  });

  test('executive manages roles and user accounts (multi-select validation, effective authorities, sessionsRevoked)', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Administration' }).click();

    await page.getByRole('tab', { name: 'Roles' }).click();
    await expect(page.getByRole('table', { name: 'Roles' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Edit EXECUTIVE' })).toBeDisabled();
    await page.getByRole('button', { name: 'New role' }).click();
    const role = page.getByRole('dialog', { name: 'New role' });
    await role.getByLabel(/^Code/).fill('PAYROLL_CLERK');
    await role.getByLabel(/^Name/).fill('Payroll clerk');
    await role.getByLabel(/^Minimum grade/).fill('2');
    await role.getByLabel(/^Maximum grade/).fill('5');
    await role.getByRole('button', { name: 'Create' }).click();
    await expect(role.getByRole('alert')).toContainText(/at least one permission/i);
    await role.getByRole('checkbox', { name: 'PAYROLL:VIEW' }).check();
    await role.getByRole('button', { name: 'Create' }).click();
    await expect(page.getByRole('row', { name: /PAYROLL_CLERK/ })).toBeVisible();

    await page.getByRole('tab', { name: 'Users' }).click();
    const users = page.getByRole('table', { name: 'User accounts' });
    await expect(users).toBeVisible();
    await expect(page.getByRole('button', { name: `Assign roles to ${SEED_ACCOUNTS.executive.email}` })).toBeDisabled();
    const manager = users.getByRole('row', { name: new RegExp(SEED_ACCOUNTS.manager.email) });
    await expect(manager.getByRole('list', { name: /Effective authorities/ })).not.toContainText('PAYROLL:EDIT');
    await manager.getByRole('button', { name: /^Assign roles/ }).click();
    const assign = page.getByRole('dialog', { name: /^Roles for/ });
    await assign.getByRole('checkbox', { name: /^MANAGER/ }).uncheck();
    await assign.getByRole('button', { name: 'Save' }).click();
    await expect(assign.getByRole('alert')).toContainText(/at least one role/i);
    await assign.getByRole('checkbox', { name: /^MANAGER/ }).check();
    await assign.getByRole('checkbox', { name: /^PAYROLL_CLERK/ }).check();
    await assign.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText(/session\(s\) revoked/)).toBeVisible();
    await expect(manager).toContainText('MANAGER, PAYROLL_CLERK');
    await expect(manager.getByRole('list', { name: /Effective authorities/ })).toContainText('PAYROLL:VIEW');

    await page.getByRole('tab', { name: 'Roles' }).click();
    await expect(page.getByRole('button', { name: 'Delete PAYROLL_CLERK' })).toBeDisabled();
  });

  test('manager (ADMIN:VIEW only) sees the new tabs read-only', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Administration' }).click();
    await page.getByRole('tab', { name: 'Holidays' }).click();
    await expect(page.getByRole('table', { name: 'Holidays' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New holiday' })).toHaveCount(0);
    await page.getByRole('tab', { name: 'Users' }).click();
    await expect(page.getByRole('table', { name: 'User accounts' })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Assign roles|^Change status/ })).toHaveCount(0);
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
