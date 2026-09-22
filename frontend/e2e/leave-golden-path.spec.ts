import { expect, test } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * Phase 2 Leave golden path (COMPONENT_MAPPING.md §5). Here it runs against the msw browser
 * worker (`VITE_MOCK_API=true`); the integration session runs the same spec against the real
 * stack (`E2E_REAL_STACK=1`, PostgreSQL-backed leave-service).
 */

function iso(offset: number) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10);
}

async function login(page: import('@playwright/test').Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

test.describe('P2 leave golden path', () => {
  test.skip(!process.env.VITE_MODULE_FLAGS?.includes('leave='), 'requires leave module promotion (VITE_MODULE_FLAGS=leave=NEW)');
  test.skip(process.env.E2E_REAL_STACK === '1', 'mock-only here: relies on msw leave fixtures in src/mocks/leaveStore.ts; the integration session owns the real-stack run');

  test('staff submits a request, sees it pending with live day count, then cancels it', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.staff.email);
    await page.getByRole('link', { name: 'Leave' }).click();
    await page.getByRole('tab', { name: 'Submit Request' }).click();
    await expect(page.getByRole('heading', { name: 'Submit Request' })).toBeVisible();

    await page.getByLabel(/Leave type/).selectOption({ label: 'Paid Time Off' });
    await expect(page.getByTestId('available-balance')).toContainText('Paid Time Off');
    const day = iso(45);
    await page.getByLabel('Start date').fill(day);
    await page.getByLabel('End date').fill(day);
    await expect(page.getByTestId('business-days')).toHaveText('1');
    await page.getByLabel('Reason').fill('Golden path');
    await page.getByRole('button', { name: 'Submit Request' }).click();

    await expect(page.getByText('Leave request submitted for approval')).toBeVisible();
    await expect(page).toHaveURL(/\/leave$/);
    const row = page.getByRole('row', { name: /Golden path/ });
    await expect(row).toContainText('PENDING');

    await row.getByRole('button', { name: /Cancel request/ }).click();
    await page.getByLabel('Reason').fill('Golden path cancel');
    await page.getByRole('button', { name: 'Confirm cancel' }).click();
    await expect(page.getByText('Leave request cancelled')).toBeVisible();
    await expect(page.getByRole('row', { name: /Golden path cancel/ })).toContainText('CANCELLED');
  });

  test('manager approves a pending request and it appears on the team calendar', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Leave' }).click();
    await page.getByRole('tab', { name: 'Approvals' }).click();
    const row = page.getByRole('row', { name: /EMILY JOHNSON/ });
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: /Approve request/ }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Approve' }).click();
    await expect(page.getByText('Leave request approved')).toBeVisible();
    await expect(page.getByRole('row', { name: /EMILY JOHNSON/ })).toHaveCount(0);

    await page.getByRole('tab', { name: 'Team Calendar' }).click();
    await expect(page.getByRole('row', { name: /EMILY JOHNSON/ })).toContainText('APPROVED');
  });

  test('reject requires comments from the generated schema', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Leave' }).click();
    await page.getByRole('tab', { name: 'Approvals' }).click();
    await page.getByRole('button', { name: /Reject request/ }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: 'Reject' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('A rejection reason is required');
  });
});
