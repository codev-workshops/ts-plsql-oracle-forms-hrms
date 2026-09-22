import { expect, test, type Page } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

const realStack = process.env.E2E_REAL_STACK === '1';

async function login(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
}

/** First Monday at least `weeks` weeks ahead (yyyy-mm-dd), so the range is never in the -20211 past window. */
function mondayPlusWeeks(weeks: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + weeks * 7);
  while (d.getUTCDay() !== 1) d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/**
 * MOCK-ONLY (msw browser worker). Golden path from COMPONENT_MAPPING.md §5 / CUTOVER_PLAN.md §6:
 * staff submits an annual-leave request (live business-day count + balance), sees it PENDING in
 * My Requests, manager approves a sibling pending request and rejects another one with a
 * mandatory comment, staff cancels its own PENDING request. The deterministic fixtures live in
 * src/mocks/leaveStore.ts; the real end-to-end run against Spring Boot + PostgreSQL is the
 * integration session's job (`E2E_REAL_STACK=1`).
 */
test.describe('P2 leave golden path (mock stack)', () => {
  test.skip(!process.env.VITE_MODULE_FLAGS?.includes('leave='), 'requires leave module promotion');
  test.skip(realStack, 'mock-only: relies on msw leave fixtures absent from tools/fixtures/pg');

  test('staff submits and cancels, manager approves and rejects', async ({ page }) => {
    const start = mondayPlusWeeks(8);

    await login(page, SEED_ACCOUNTS.staff.email);
    await page.getByRole('link', { name: 'Leave' }).click();
    await expect(page.getByRole('table', { name: 'Leave balances' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'My leave requests' })).toBeVisible();

    await page.getByRole('tab', { name: 'Submit Request' }).click();
    await page.getByLabel('Leave type').selectOption({ label: 'Paid Time Off' });
    await page.getByLabel('Start date *').fill(start);
    await page.getByLabel('End date *').fill(start);
    await expect(page.getByTestId('business-days')).toHaveText(/^1/);
    await expect(page.getByTestId('available-balance')).toBeVisible();
    await page.getByLabel('Reason').fill('E2E golden path');
    await page.getByRole('button', { name: 'Submit Request' }).click();
    await expect(page.getByText('Leave request submitted')).toBeVisible();
    const newRow = page.getByRole('row', { name: /E2E golden path/ });
    await expect(newRow.locator('[data-status="PENDING"]')).toBeVisible();
    const cancelButton = newRow.getByRole('button', { name: /Cancel request/ });
    const requestId = (await cancelButton.getAttribute('aria-label'))!.replace('Cancel request ', '');

    await cancelButton.click();
    await page.getByRole('button', { name: 'Confirm cancellation' }).click();
    await expect(page.getByText('Leave request cancelled')).toBeVisible();
    const submitted = page.getByRole('row', { name: new RegExp(`Cancel request ${requestId}$`) });
    await expect(submitted.locator('[data-status="CANCELLED"]')).toBeVisible();
    await expect(submitted.getByRole('button', { name: /Cancel request/ })).toBeDisabled();
    await page.getByRole('button', { name: 'Logout' }).click();

    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Leave' }).click();
    await page.getByRole('tab', { name: 'Approvals' }).click();
    const approvals = page.getByRole('table', { name: 'Pending leave approvals' });
    await expect(approvals).toBeVisible();
    const before = await approvals.getByRole('button', { name: 'Approve' }).count();
    expect(before).toBeGreaterThanOrEqual(2);

    await approvals.getByRole('button', { name: 'Approve' }).first().click();
    await expect(approvals.getByRole('button', { name: 'Approve' })).toHaveCount(before - 1);

    await approvals.getByRole('button', { name: 'Reject' }).first().click();
    await page.getByRole('button', { name: 'Reject' }).last().click();
    await expect(page.getByText('A rejection reason is required')).toBeVisible();
    await page.getByLabel('Rejection reason *').fill('Coverage gap during release week.');
    await page.getByRole('dialog').getByRole('button', { name: 'Reject' }).click();
    await expect(approvals.getByRole('button', { name: 'Approve' })).toHaveCount(before - 2);

    await page.getByRole('tab', { name: 'Team Calendar' }).click();
    await expect(page.getByRole('table', { name: 'Team leave calendar' })).toBeVisible();
    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});
