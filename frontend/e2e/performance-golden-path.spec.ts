import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';
import { PG_PERFORMANCE_FIXTURES, SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

const realStack = process.env.E2E_REAL_STACK === '1';
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';

async function login(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function logout(page: Page) {
  await page.getByRole('button', { name: 'Logout' }).click();
  await expect(page).toHaveURL(/\/login$/);
}

/** API context authenticated as `email` (contracts/p0-foundation/openapi.yaml `POST /api/auth/login`). */
async function apiAs(email: string): Promise<APIRequestContext> {
  const anonymous = await request.newContext({ baseURL });
  const login = await anonymous.post('/api/auth/login', { data: { username: email, password: SEED_PASSWORD } });
  expect(login.ok(), `login ${email}: ${login.status()}`).toBeTruthy();
  const { accessToken } = (await login.json()) as { accessToken: string };
  await anonymous.dispose();
  return request.newContext({ baseURL, extraHTTPHeaders: { authorization: `Bearer ${accessToken}` } });
}

interface CycleRow { cycleId: number; cycleName: string; status: string }
interface ReviewRow { reviewId: number; empId: number; reviewerEmpId: number; status: string }

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

/**
 * REAL-STACK, mutating. Runs against frontend + performance-service (`HRMS_FLAG_PERFORMANCE=NEW`)
 * + PostgreSQL seeded with tools/fixtures/pg. Test data is isolated per run: an executive creates,
 * opens and generates reviews for a uniquely named cycle through the frozen API
 * (contracts/p1-performance/openapi.yaml), the UI drives every state transition of one review
 * (SARAH CHEN, whose seeded `manager_id` is the executive - the only reviewee/reviewer pair with
 * two logins), and teardown closes the cycle. The committed 2024 Mid-Year rows are never touched
 * and the walk ends in ACKNOWLEDGED, so the pending-approval population (MANAGER_REVIEW rows,
 * VW_PENDING_APPROVALS in tests/golden/views-baseline.csv) is identical before and after the run;
 * the other generated rows stay NOT_STARTED inside a CLOSED cycle (no delete endpoint exists in
 * the contract).
 */
test.describe('P1 performance write golden path (real stack)', () => {
  test.skip(!process.env.VITE_MODULE_FLAGS?.includes('performance='), 'requires performance module promotion');
  test.skip(!realStack, 'real-stack only: drives performance-service state transitions on PostgreSQL');

  const reviewee = SEED_ACCOUNTS.staffOfExecutive;
  const reviewer = SEED_ACCOUNTS.executive;
  const cycleName = `E2E write path ${Date.now()}`;
  const goalTitle = `E2E goal ${Date.now()}`;
  let admin: APIRequestContext;
  let cycle: CycleRow;
  let review: ReviewRow;

  test.beforeAll(async () => {
    admin = await apiAs(reviewer.email);
    const year = new Date().getFullYear();
    const created = await admin.post('/api/performance/cycles', {
      data: { cycleName, cycleYear: year, startDate: `${year}-01-01`, endDate: `${year}-12-31` },
    });
    expect(created.status(), await created.text()).toBe(201);
    cycle = (await created.json()) as CycleRow;
    const opened = await admin.post(`/api/performance/cycles/${cycle.cycleId}/open`);
    expect(opened.status(), await opened.text()).toBe(200);
    const generated = await admin.post(`/api/performance/cycles/${cycle.cycleId}/generate-reviews`);
    expect(generated.status(), await generated.text()).toBe(200);
    const listed = await admin.get(`/api/performance/cycles/${cycle.cycleId}/reviews`, { params: { size: 100 } });
    expect(listed.status(), await listed.text()).toBe(200);
    const { content } = (await listed.json()) as { content: ReviewRow[] };
    const found = content.find((row) => row.empId === reviewee.empId);
    expect(found, `generated review for emp ${reviewee.empId}`).toBeDefined();
    review = found!;
    expect(review.reviewerEmpId).toBe(reviewer.empId);
    expect(review.status).toBe('NOT_STARTED');
  });

  test.afterAll(async () => {
    if (!cycle) {
      await admin?.dispose();
      return;
    }
    let closed = await admin.post(`/api/performance/cycles/${cycle.cycleId}/close`);
    if (closed.status() === 401) {
      await admin.dispose();
      admin = await apiAs(reviewer.email);
      closed = await admin.post(`/api/performance/cycles/${cycle.cycleId}/close`);
    }
    const closeStatus = closed.status();
    const closeBody = await closed.text();
    await admin.dispose();
    expect(closeStatus, closeBody).toBe(200);
  });

  test('reviewee self-assesses and adds a goal, reviewer rates, reviewee completes goal and acknowledges', async ({ page }) => {
    await login(page, reviewee.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await page.getByRole('tab', { name: 'My Reviews' }).click();
    const mine = page.getByRole('row', { name: new RegExp(cycleName) });
    await expect(mine.locator('[data-status="NOT_STARTED"]')).toBeVisible();
    await mine.getByRole('button', { name: 'Open' }).click();
    await page.getByLabel('Self-assessment').fill('Delivered the Phase 1 performance cutover tests.');
    await page.getByRole('button', { name: 'Submit' }).click();
    await expect(page.getByText('Self-assessment submitted')).toBeVisible();
    await expect(mine.locator('[data-status="MANAGER_REVIEW"]')).toBeVisible();

    await page.getByRole('tab', { name: 'Goals' }).click();
    await page.getByLabel('Review').selectOption({ label: `${reviewee.displayName} — ${cycle.cycleId}` });
    await page.getByRole('button', { name: 'Add goal' }).click();
    const goalDialog = page.getByRole('dialog', { name: 'Add goal' });
    await goalDialog.getByLabel('Goal title').fill(goalTitle);
    await goalDialog.getByLabel('Category').selectOption('DEVELOPMENT');
    await goalDialog.getByLabel('Weight %').fill('40');
    await goalDialog.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Goal added')).toBeVisible();
    const goalRow = page.getByRole('row', { name: new RegExp(goalTitle) });
    await expect(goalRow.locator('[data-status="NOT_STARTED"]')).toBeVisible();
    await expect(goalRow).toContainText('DEVELOPMENT');
    await logout(page);

    await login(page, reviewer.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await page.getByRole('tab', { name: 'Team' }).click();
    await page.getByLabel('Cycle').selectOption({ label: cycleName });
    const teamRow = page.getByRole('row', { name: new RegExp(reviewee.displayName) });
    await expect(teamRow.locator('[data-status="MANAGER_REVIEW"]')).toBeVisible();
    await teamRow.getByRole('button', { name: 'Review' }).click();
    await expect(page.getByText('Delivered the Phase 1 performance cutover tests.')).toBeVisible();
    await page.getByLabel('Overall rating').fill('4.6');
    await page.getByLabel('Manager assessment').fill('Excellent delivery.');
    await page.getByRole('button', { name: 'Submit' }).click();
    await expect(page.getByText('Manager review submitted')).toBeVisible();
    await expect(teamRow.locator('[data-status="COMPLETED"]')).toBeVisible();
    await expect(teamRow).toContainText('4.6');
    await expect(teamRow).toContainText('Exceptional');
    await logout(page);

    await login(page, reviewee.email);
    await page.getByRole('link', { name: 'Performance' }).click();
    await page.getByRole('tab', { name: 'Goals' }).click();
    await page.getByLabel('Review').selectOption({ label: `${reviewee.displayName} — ${cycle.cycleId}` });
    await expect(page.getByRole('button', { name: 'Add goal' })).toHaveCount(0);
    await goalRow.getByRole('button', { name: 'Progress' }).click();
    await page.getByLabel('Progress %').fill('100');
    await page.getByRole('dialog', { name: 'Update progress' }).getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Progress updated')).toBeVisible();
    await expect(goalRow.locator('[data-status="COMPLETED"]')).toBeVisible();
    await expect(goalRow).toContainText('100');

    await page.getByRole('tab', { name: 'My Reviews' }).click();
    await expect(mine.locator('[data-status="COMPLETED"]')).toBeVisible();
    await expect(mine).toContainText('Exceptional');
    await mine.getByRole('button', { name: 'Open' }).click();
    await page.getByLabel('Employee comments').fill('Thanks for the feedback.');
    await page.getByRole('button', { name: 'Acknowledge' }).click();
    await expect(page.getByText('Review acknowledged')).toBeVisible();
    await expect(mine.locator('[data-status="ACKNOWLEDGED"]')).toBeVisible();

    await page.getByRole('tab', { name: 'Goals' }).click();
    await page.getByLabel('Review').selectOption({ label: `${reviewee.displayName} — ${cycle.cycleId}` });
    await expect(goalRow).toBeVisible();
    await expect(goalRow.getByRole('button', { name: 'Progress' })).toHaveCount(0);
    await logout(page);

    // Reconciliation stays interpretable: nothing this run created is left in MANAGER_REVIEW.
    const after = await admin.get(`/api/performance/cycles/${cycle.cycleId}/reviews`, { params: { size: 100 } });
    expect(after.status(), await after.text()).toBe(200);
    const { content } = (await after.json()) as { content: ReviewRow[] };
    expect(content.find((row) => row.reviewId === review.reviewId)?.status).toBe('ACKNOWLEDGED');
    expect(content.filter((row) => row.status === 'MANAGER_REVIEW')).toEqual([]);
  });
});
