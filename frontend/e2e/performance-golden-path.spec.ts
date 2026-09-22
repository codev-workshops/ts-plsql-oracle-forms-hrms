import { expect, test, type Page } from '@playwright/test';
import { PG_PERFORMANCE_FIXTURES, SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

const realStack = process.env.E2E_REAL_STACK === '1';

async function login(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
}

/**
 * MOCK-ONLY (msw browser worker). The mutating golden path depends on the deterministic
 * msw fixtures in src/mocks/performanceStore.ts ('FY2025 Annual Review', EMILY JOHNSON with a
 * pre-existing MANAGER_REVIEW row, a DRAFT cycle to open) which are intentionally absent
 * from tools/fixtures/pg: the PG performance rows feed VW_PENDING_APPROVALS in
 * tests/golden/views-baseline.csv and must not be mutated by a browser run.
 */
test.describe('P1 performance golden path (mock stack)', () => {
  test.skip(!process.env.VITE_MODULE_FLAGS?.includes('performance='), 'requires performance module promotion');
  test.skip(realStack, 'mock-only: relies on msw performance fixtures absent from tools/fixtures/pg');

  test('staff submits self-assessment, manager completes review, executive opens cycle', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.staff.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await page.getByRole('tab', { name: 'My Reviews' }).click();
    await page.getByRole('button', { name: 'Open' }).first().click();
    await page.getByLabel('Self-assessment').fill('I delivered Phase 1 improvements.');
    await page.getByRole('button', { name: 'Submit' }).click();
    await expect(page.locator('[data-status="MANAGER_REVIEW"]').first()).toBeVisible();
    await page.getByRole('button', { name: 'Logout' }).click();

    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await page.getByRole('tab', { name: 'Team' }).click();
    await page.getByLabel('Cycle').selectOption({ label: 'FY2025 Annual Review' });
    const emily = page.getByRole('row', { name: /EMILY JOHNSON/ });
    await emily.getByRole('button', { name: 'Review' }).click();
    await page.getByLabel('Overall rating').fill('4.6');
    await page.getByLabel('Manager assessment').fill('Excellent delivery.');
    await page.getByRole('button', { name: 'Submit' }).click();
    await expect(page.locator('[data-status="COMPLETED"]').first()).toBeVisible();
    await expect(page.getByText('Exceptional').first()).toBeVisible();
    // Team grid refreshes without a reload (round-1 finding).
    await expect(emily.locator('[data-status="COMPLETED"]')).toBeVisible();
    await expect(emily).toContainText('4.6');
    await page.getByRole('button', { name: 'Logout' }).click();

    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await expect(page.getByRole('tab', { name: 'Review Cycles' })).toBeVisible();
    await page.getByRole('button', { name: 'Open cycle' }).last().click();
    await expect(page.locator('[data-status="OPEN"]').last()).toBeVisible();
  });
});

/**
 * REAL-STACK, read-only. Runs against frontend + performance-service + PostgreSQL seeded
 * with tools/fixtures/pg; asserts the Team tab renders the committed PERFORMANCE_REVIEWS rows
 * (e2e/seed-accounts.ts PG_PERFORMANCE_FIXTURES) for their reviewer without mutating them.
 */
test.describe('P1 performance team tab (real stack)', () => {
  test.skip(!process.env.VITE_MODULE_FLAGS?.includes('performance='), 'requires performance module promotion');
  test.skip(!realStack, 'real-stack only: reads tools/fixtures/pg rows that are not part of the msw fixtures');

  test('manager sees direct-report reviews seeded in tools/fixtures/pg', async ({ page }) => {
    const { cycle, reviews } = PG_PERFORMANCE_FIXTURES;
    await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await page.getByRole('tab', { name: 'Team' }).click();
    await page.getByLabel('Cycle').selectOption({ label: cycle.cycleName });
    for (const review of reviews) {
      const row = page.getByRole('row', { name: new RegExp(review.employeeName) });
      await expect(row.locator(`[data-status="${review.status}"]`)).toBeVisible();
    }
    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});
