import { expect, test } from '@playwright/test';
import { login, requireRealStack } from './helpers';

const HR = 'patricia.williams@company.com'; // emp 10, VP-HR grade 9

test.describe('employee', () => {
  requireRealStack();

  test('HR hires, edits, terminates and rehires an employee', async ({ page }) => {
    const suffix = `${Date.now()}`;
    const email = `e2e.hire.${suffix}@company.com`;
    const hireDate = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10);
    await login(page, HR);
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Employees' }).click();
    await expect(page.getByRole('heading', { name: 'Employees' })).toBeVisible();
    await page.getByRole('link', { name: 'New hire' }).click();
    await page.getByLabel('First name').fill('e2e');
    await page.getByLabel('Last name').fill(`hire${suffix}`);
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Hire date').fill(hireDate);
    await page.getByLabel('Department').selectOption({ label: 'Finance' });
    await page.getByLabel('Job').selectOption({ label: 'Senior Accountant' });
    await page.getByLabel('Manager employee ID').fill('21');
    await page.getByLabel('Base salary').fill('10000');
    await page.getByRole('button', { name: 'Create employee' }).click();
    await expect(page.getByRole('heading', { name: `E2E HIRE${suffix}` })).toBeVisible();
    await expect(page.getByText(/below minimum for grade G4/)).toBeVisible();

    await page.getByLabel('City').fill('Brooklyn');
    await page.getByRole('button', { name: 'Save details' }).click();
    await expect(page.getByText('Saved')).toBeVisible();

    await page.getByRole('tab', { name: 'Emergency contacts' }).click();
    await page.getByLabel('Contact name').fill('E2E Contact');
    await page.getByLabel('Primary phone').fill('212-555-0101');
    await page.getByRole('button', { name: 'Add contact' }).click();
    await expect(page.getByRole('cell', { name: 'E2E Contact' })).toBeVisible();

    await page.getByRole('tab', { name: 'Lifecycle' }).click();
    await page.getByLabel('Effective date').fill(hireDate);
    await page.getByLabel('Reason').fill('VOLUNTARY');
    await page.getByRole('button', { name: 'Terminate' }).click();
    await expect(page.getByText('Termination complete')).toBeVisible();
    await expect(page.getByText('TERMINATED', { exact: true })).toBeVisible();
    const rehireDate = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
    await page.getByLabel('Effective date').fill(rehireDate);
    await page.getByRole('button', { name: 'Rehire' }).click();
    await expect(page.getByText('Rehire complete')).toBeVisible();
    await expect(page.getByText('ACTIVE', { exact: true })).toBeVisible();

    await page.getByRole('tab', { name: 'History' }).click();
    await expect(page.getByRole('cell', { name: 'REHIRE' })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'TERMINATION' })).toBeVisible();
  });

  test('search finds seeded employees by last-name prefix', async ({ page }) => {
    await login(page, 'thomas.baker@company.com');
    await page.goto('/employees');
    await page.getByLabel('Last name').fill('bak');
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByRole('link', { name: 'THOMAS BAKER' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'New hire' })).toHaveCount(0);
  });
});
