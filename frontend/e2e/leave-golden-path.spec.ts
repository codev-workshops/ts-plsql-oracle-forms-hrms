import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * Phase 2 Leave golden path (COMPONENT_MAPPING.md §5). The first block runs against the msw
 * browser worker (`VITE_MOCK_API=true`); the blocks below it run against the real stack
 * (`E2E_REAL_STACK=1`, PostgreSQL-backed leave-service, `HRMS_FLAG_LEAVE=NEW`).
 */

const realStack = process.env.E2E_REAL_STACK === '1';
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';
const leaveEnabled = !!process.env.VITE_MODULE_FLAGS?.includes('leave=');

function iso(offset: number) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10);
}

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

async function openLeaveTab(page: Page, tab: 'My Requests' | 'Submit Request' | 'Approvals' | 'Team Calendar') {
  await page.getByRole('link', { name: 'Leave' }).click();
  await page.getByRole('tab', { name: tab }).click();
  await expect(page.getByRole('heading', { name: tab })).toBeVisible();
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

test.describe('P2 leave golden path', () => {
  test.skip(!leaveEnabled, 'requires leave module promotion (VITE_MODULE_FLAGS=leave=NEW)');
  test.skip(realStack, 'mock-only here: relies on msw leave fixtures in src/mocks/leaveStore.ts; the real-stack blocks below cover PostgreSQL');

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

interface LeaveRequestRow { requestId: number; status: string; reason: string | null; startDate: string }
interface BusinessDaysRow { businessDays: number }

const E2E_MARKER = 'E2E P2 leave';

/**
 * Every row this file writes carries `E2E_MARKER` in its reason so it can be told apart from the
 * committed tools/fixtures/pg rows (request_id 1001-1005) in the six-view diagnostics. Leftovers of an
 * interrupted run (PENDING/APPROVED) would otherwise trip the `-20202` overlap rule, so they are
 * cancelled through the frozen contract before each run; a completed run ends with every marker row
 * CANCELLED (or REJECTED for the reject path), which `VW_PENDING_APPROVALS` ignores.
 */
async function cancelMarkerRows(owner: APIRequestContext): Promise<number> {
  const mine = await owner.get('/api/leave/requests/mine');
  expect(mine.status(), await mine.text()).toBe(200);
  const rows = (await mine.json()) as LeaveRequestRow[];
  let cancelled = 0;
  for (const row of rows) {
    if (!row.reason?.startsWith(E2E_MARKER) || !['PENDING', 'APPROVED'].includes(row.status)) continue;
    const res = await owner.post(`/api/leave/requests/${row.requestId}/cancel`, { data: { reason: `${E2E_MARKER} stale cleanup` } });
    expect(res.status(), await res.text()).toBe(200);
    cancelled += 1;
  }
  return cancelled;
}

/** First weekday >= `fromOffset` days ahead that the server counts as one business day (BUG-05 observed holidays). */
async function pickBusinessDay(api: APIRequestContext, fromOffset: number): Promise<string> {
  for (let offset = fromOffset; offset < fromOffset + 30; offset += 1) {
    const day = iso(offset);
    const res = await api.get('/api/leave/business-days', { params: { start: day, end: day } });
    expect(res.status(), await res.text()).toBe(200);
    if (((await res.json()) as BusinessDaysRow).businessDays === 1) return day;
  }
  throw new Error('no business day found in the next 30 days');
}

/**
 * REAL-STACK, read-only. The seeded LEAVE_BALANCES rows are for calendar year 2024 and the
 * balance check reads the *current* year's row (QUIRK-01/02: no row -> `available = 0`), so an
 * accrual-type (PTO) request is rejected with `-20201` before anything is written. This asserts
 * the frozen message (contracts/p2-leave/error-codes.md §1) surfaces on the `leaveTypeId` field
 * and that the JWT-scoped views of a STAFF user without direct reports are empty.
 */
test.describe('P2 leave read-only checks (real stack)', () => {
  test.skip(!leaveEnabled, 'requires leave module promotion (VITE_MODULE_FLAGS=leave=NEW)');
  test.skip(!realStack, 'real-stack only: depends on tools/fixtures/pg balances and the PostgreSQL leave-service');

  test('staff sees empty current-year PG balances, gets the frozen -20201 message for PTO', async ({ page }) => {
    const api = await apiAs(SEED_ACCOUNTS.staff.email);
    const day = await pickBusinessDay(api, 40);
    // emp 11 owns exactly one seeded balance row (balance_id 9003) and it is for 2024, so the
    // current-year grid (GET /api/leave/balances/mine without ?year) is empty.
    const currentYear = await api.get('/api/leave/balances/mine');
    expect(currentYear.status(), await currentYear.text()).toBe(200);
    expect(await currentYear.json()).toEqual([]);
    const seeded = await api.get('/api/leave/balances/mine', { params: { year: 2024 } });
    expect(seeded.status(), await seeded.text()).toBe(200);
    expect((await seeded.json()) as { available: number }[]).toHaveLength(1);
    await api.dispose();

    await login(page, SEED_ACCOUNTS.staff.email);
    await openLeaveTab(page, 'My Requests');
    await expect(page.getByRole('region', { name: 'Leave balances' })).toContainText('No balances');
    await expect(page.getByText('No leave requests')).toBeVisible();

    await openLeaveTab(page, 'Approvals');
    await expect(page.getByText('No pending approvals')).toBeVisible();
    await openLeaveTab(page, 'Team Calendar');
    await expect(page.getByText('No approved leave in this range')).toBeVisible();

    await openLeaveTab(page, 'Submit Request');
    await page.getByLabel(/Leave type/).selectOption({ label: 'Paid Time Off' });
    await expect(page.getByTestId('available-balance')).toHaveText('0 day(s) of Paid Time Off');
    await page.getByLabel('Start date').fill(day);
    await page.getByLabel('End date').fill(day);
    await expect(page.getByTestId('business-days')).toHaveText('1');
    await expect(page.getByText('Requested days exceed the available balance')).toBeVisible();
    await page.getByLabel('Reason').fill(`${E2E_MARKER} insufficient balance (never persisted)`);
    await page.getByRole('button', { name: 'Submit Request' }).click();
    await expect(page.getByRole('alert')).toHaveText('Insufficient leave balance. Available: 0, Requested: 1');
    await expect(page).toHaveURL(/\/leave\/submit$/);
    await logout(page);
  });

  test('reject dialog requires comments from the generated schema before any request is sent', async ({ page }) => {
    // Jennifer Park (emp 21) is the seeded approver of request 1001/1003 (VW_PENDING_APPROVALS rows).
    await login(page, SEED_ACCOUNTS.manager.email);
    await openLeaveTab(page, 'Approvals');
    const row = page.getByRole('row', { name: /THOMAS BAKER/ });
    await expect(row).toContainText('Summer vacation');
    await row.getByRole('button', { name: /Reject request/ }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: 'Reject' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('A rejection reason is required');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(row).toBeVisible();
    await logout(page);
  });
});

/**
 * REAL-STACK, mutating. Two distinct seeded logins: SARAH CHEN (emp 2, STAFF) whose `manager_emp_id`
 * is JAMES RICHARDSON (emp 1, EXECUTIVE) - the only requester/approver pair with two logins. The
 * request uses `Compensatory Time` (accrual_flag = 'N', requires_approval = 'Y', min_tenure 90 days)
 * so the 2024-only balance fixtures neither block it (`-20201`) nor change: approve/cancel adjust
 * the *start-date year's* balance row, which does not exist for a future date (QUIRK-02 no-op).
 * Order: submit -> PENDING (My Requests) -> approve (Approvals) -> APPROVED on Team Calendar ->
 * owner cancels -> CANCELLED, leaving no PENDING/APPROVED marker rows behind.
 */
test.describe('P2 leave write golden path (real stack)', () => {
  test.skip(!leaveEnabled, 'requires leave module promotion (VITE_MODULE_FLAGS=leave=NEW)');
  test.skip(!realStack, 'real-stack only: drives leave-service state transitions on PostgreSQL');

  const requester = SEED_ACCOUNTS.staffOfExecutive;
  const approver = SEED_ACCOUNTS.executive;
  const reason = `${E2E_MARKER} ${Date.now()}`;
  let owner: APIRequestContext;
  let day: string;

  test.beforeAll(async () => {
    owner = await apiAs(requester.email);
    const stale = await cancelMarkerRows(owner);
    if (stale > 0) console.log(`leave-golden-path: cancelled ${stale} stale ${E2E_MARKER} row(s) before the run`);
    day = await pickBusinessDay(owner, 35);
  });

  test.afterAll(async () => {
    const left = await cancelMarkerRows(owner);
    expect(left, 'a completed run leaves no PENDING/APPROVED marker rows').toBe(0);
    await owner.dispose();
  });

  test('staff submits COMP leave, executive approves it, team calendar shows it, owner cancels it', async ({ page }) => {
    await login(page, requester.email);
    await openLeaveTab(page, 'Submit Request');
    await page.getByLabel(/Leave type/).selectOption({ label: 'Compensatory Time' });
    await expect(page.getByRole('list', { name: 'Leave type policy' })).toContainText('Requires at least 90 days of tenure.');
    await page.getByLabel('Start date').fill(day);
    await page.getByLabel('End date').fill(day);
    await expect(page.getByTestId('business-days')).toHaveText('1');
    await page.getByLabel('Reason').fill(reason);
    await page.getByRole('button', { name: 'Submit Request' }).click();
    await expect(page.getByText('Leave request submitted for approval')).toBeVisible();
    await expect(page).toHaveURL(/\/leave$/);
    const mine = page.getByRole('row', { name: new RegExp(reason) });
    await expect(mine).toContainText('Compensatory Time');
    await expect(mine).toContainText('PENDING');
    await expect(mine.getByRole('button', { name: /Cancel request/ })).toBeVisible();
    await logout(page);

    await login(page, approver.email);
    await openLeaveTab(page, 'Approvals');
    const pending = page.getByRole('row', { name: new RegExp(reason) });
    await expect(pending).toContainText(requester.displayName);
    await expect(pending).toContainText(requester.empNumber);
    await pending.getByRole('button', { name: /Approve request/ }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText(`Reason: ${reason}`);
    await dialog.getByLabel('Comments').fill(`${E2E_MARKER} approved`);
    await dialog.getByRole('button', { name: 'Approve' }).click();
    await expect(page.getByText('Leave request approved')).toBeVisible();
    await expect(page.getByRole('row', { name: new RegExp(reason) })).toHaveCount(0);

    await openLeaveTab(page, 'Team Calendar');
    await page.getByLabel('From').fill(day);
    await page.getByLabel('To').fill(day);
    await page.getByRole('button', { name: 'Apply' }).click();
    const calendar = page.getByRole('row', { name: new RegExp(requester.displayName) });
    await expect(calendar).toContainText('Compensatory Time');
    await expect(calendar).toContainText('APPROVED');
    await logout(page);

    await login(page, requester.email);
    await openLeaveTab(page, 'My Requests');
    const approved = page.getByRole('row', { name: new RegExp(reason) });
    await expect(approved).toContainText('APPROVED');
    await expect(approved).toContainText(approver.displayName);
    await approved.getByRole('button', { name: /Cancel request/ }).click();
    // Cancel overwrites LEAVE_REQUESTS.REASON (frozen legacy quirk), so the run id keeps this row
    // distinguishable from earlier runs' CANCELLED history in the six-view diagnostics.
    const cancelReason = `${reason} cleanup`;
    await page.getByRole('dialog').getByLabel('Reason').fill(cancelReason);
    await page.getByRole('button', { name: 'Confirm cancel' }).click();
    await expect(page.getByText('Leave request cancelled')).toBeVisible();
    const cancelled = page.getByRole('row', { name: new RegExp(cancelReason) });
    await expect(cancelled).toContainText('CANCELLED');
    await expect(cancelled.getByRole('button', { name: /Cancel request/ })).toHaveCount(0);
    await logout(page);
  });

  test('a second user cannot see the requester\'s rows or approvals (JWT empId scoping)', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.manager.email);
    await openLeaveTab(page, 'My Requests');
    await expect(page.getByRole('row', { name: new RegExp(E2E_MARKER) })).toHaveCount(0);
    await openLeaveTab(page, 'Approvals');
    await expect(page.getByRole('row', { name: new RegExp(E2E_MARKER) })).toHaveCount(0);
    await logout(page);
  });
});
