import { expect, test, type Page } from '@playwright/test';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * P3 integration-session real-stack golden path (React ↔ Spring ↔ PostgreSQL, no msw).
 * Stage 1 runs on the employee=NEW_READONLY stack, stage 2 on the employee=NEW stack; the
 * Playwright project decides which stack via baseURL + STAGE env.
 */
const RUN = Date.now().toString(36);

function iso(offset: number) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return d.toISOString().slice(0, 10);
}

async function login(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function selectByLabel(page: Page, label: string, pattern: RegExp) {
  const select = page.getByLabel(label, { exact: true });
  const value = await select.locator('option').evaluateAll(
    (opts, src) => (opts as HTMLOptionElement[]).find((o) => new RegExp(src.source, src.flags).test(o.textContent ?? ''))?.value ?? null,
    { source: pattern.source, flags: pattern.flags },
  );
  expect(value, `option matching ${pattern} in ${label}`).not.toBeNull();
  await select.selectOption(value as string);
}

async function pickManager(page: Page, q: string, empId: number) {
  await page.getByRole('searchbox', { name: 'Search Manager' }).fill(q);
  const select = page.getByLabel('Manager', { exact: true });
  await expect(select.locator(`option[value="${empId}"]`)).toHaveCount(1);
  await select.selectOption(String(empId));
}

async function apiToken(page: Page, email: string): Promise<string> {
  const res = await page.request.post('/api/auth/login', { data: { username: email, password: SEED_PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await res.json()).accessToken;
}

test.describe('P3 real stack – stage 1 employee=NEW_READONLY', () => {
  test.beforeEach(() => test.skip(!test.info().project.name.endsWith('NEW_READONLY'), 'stage 1 only'));

  test('NEW_READONLY: search, open detail, browse history/salary tabs; no write controls; backend refuses writes', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Employees' }).click();
    await expect(page.getByRole('table', { name: 'Employees' })).toBeVisible();
    await expect(page.getByTestId('read-only-banner')).toBeVisible();
    await expect(page.getByRole('button', { name: 'New employee' })).toHaveCount(0);

    await page.getByLabel('Name or number').fill('martinez');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByRole('link', { name: 'MARTINEZ, DAVID' }).click();
    await expect(page.getByRole('heading', { name: /MARTINEZ, DAVID/ })).toBeVisible();
    await expect(page.getByTestId('emp-number')).toHaveText('EMP-000011');

    await page.getByRole('tab', { name: 'History' }).click();
    // seed carries no EMPLOYEE_HISTORY rows (tools/fixtures/pg): either the grid or the empty state
    await expect(page.getByRole('region', { name: 'Employment history' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Employment history' }).or(page.getByText('No history recorded.'))).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await page.getByRole('tab', { name: 'Salary' }).click();
    await expect(page.getByTestId('current-salary')).toBeVisible();
    await expect(page.getByRole('table', { name: 'Salary history' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Change salary' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Terminate' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Save' })).toHaveCount(0);

    // backend guard (contract §2 MODULE_READ_ONLY)
    const token = await apiToken(page, SEED_ACCOUNTS.executive.email);
    const res = await page.request.post('/api/employees/11/salary', {
      headers: { Authorization: `Bearer ${token}` },
      data: { effectiveDate: iso(7), baseSalary: 1, changeReason: 'X' },
    });
    expect(res.status()).toBe(409);
    expect((await res.json()).code).toBe('MODULE_READ_ONLY');
  });
});

test.describe('P3 real stack – stage 2 employee=NEW writes', () => {
  test.beforeEach(() => test.skip(!test.info().project.name.endsWith('-NEW'), 'stage 2 only'));

  test('NEW: create (number from sequence, salary via SalaryService), change salary closes prior row, -20502, -20004, terminate + -20005 + session revoked', async ({ page }) => {
    await login(page, SEED_ACCOUNTS.executive.email);
    await page.getByRole('link', { name: 'Employees' }).click();
    await expect(page.getByRole('table', { name: 'Employees' })).toBeVisible();
    await expect(page.getByTestId('read-only-banner')).toHaveCount(0);

    // --- create employee A ---
    const emailA = `grace.hopper.${RUN}@company.com`;
    await page.getByRole('button', { name: 'New employee' }).click();
    await page.getByLabel('First name *').fill('Grace');
    await page.getByLabel('Last name *').fill('Hopper');
    await page.getByLabel('E-mail').fill(emailA);
    await page.getByLabel('Hire date *').fill(iso(7));
    await page.getByLabel('Department *').selectOption({ label: 'FIN – Finance & Accounting' });
    await page.getByLabel('Job title *').selectOption({ label: 'Accountant (G3)' });
    await selectByLabel(page, 'Manager', /KUMAR/);
    await page.getByLabel('Initial salary').fill('50000');
    await page.getByRole('button', { name: 'Create employee' }).click();
    await expect(page.getByText(/Employee EMP-\d{6} created/)).toBeVisible();
    await expect(page.getByRole('heading', { name: /Hopper, Grace/i })).toBeVisible();
    const empNumberA = (await page.getByTestId('emp-number').textContent())!.trim();
    expect(empNumberA).toMatch(/^EMP-\d{6}$/);
    expect(Number(empNumberA.slice(4))).toBeGreaterThanOrEqual(100); // SEQ_EMP_NUMBER after seed
    const idA = Number(page.url().match(/\/employees\/(\d+)/)![1]);

    // salary created through SalaryService
    await page.getByRole('tab', { name: 'Salary' }).click();
    await expect(page.getByTestId('current-salary')).toContainText('50,000.00');
    const history = page.getByRole('table', { name: 'Salary history' });
    await expect(history.locator('tbody tr')).toHaveCount(1);

    // --- change salary → previous row closed ---
    await page.getByRole('button', { name: 'Change salary' }).first().click();
    const salary = page.getByRole('dialog', { name: 'Change salary' });
    await salary.getByLabel('Effective date *').fill(iso(8));
    await salary.getByLabel('Base salary *').fill('95000');
    await expect(salary.getByTestId('grade-band-warning')).toContainText('outside the G3 grade band');
    await salary.getByLabel('Change reason *').fill('MARKET');
    await salary.getByRole('button', { name: 'Save salary' }).click();
    await expect(page.getByText('Salary set to 95,000.00 USD')).toBeVisible();
    await expect(page.getByTestId('current-salary')).toContainText('95,000.00');
    await expect(history.locator('tbody tr')).toHaveCount(2);
    await expect(history.locator('tbody tr').filter({ hasText: 'current' })).toHaveCount(1);
    await expect(history.locator('tbody tr').filter({ hasText: '50,000.00' })).not.toContainText('current');

    // --- duplicate e-mail on create → -20502 inline on E-mail ---
    await page.getByRole('link', { name: 'Employees' }).click();
    await page.getByRole('button', { name: 'New employee' }).click();
    await page.getByLabel('First name *').fill('Dup');
    await page.getByLabel('Last name *').fill('Email');
    await page.getByLabel('E-mail').fill(emailA.toUpperCase());
    await page.getByLabel('Hire date *').fill(iso(7));
    await page.getByLabel('Department *').selectOption({ label: 'FIN – Finance & Accounting' });
    await page.getByLabel('Job title *').selectOption({ label: 'Accountant (G3)' });
    await page.getByLabel('Initial salary').fill('50000');
    const dupResp = page.waitForResponse((r) => r.url().includes('/api/employees') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Create employee' }).click();
    const dup = await dupResp;
    expect(dup.status()).toBe(409);
    expect((await dup.json()).code).toBe('-20502');
    await expect(page.getByRole('alert').filter({ hasText: /Email address already in use/ })).toBeVisible();

    // --- create B reporting to A, then make A report to B → -20004 (cycle) ---
    const emailB = `ada.lovelace.${RUN}@company.com`;
    await page.getByLabel('First name *').fill('Ada');
    await page.getByLabel('Last name *').fill('Lovelace');
    await page.getByLabel('E-mail').fill(emailB);
    await pickManager(page, 'Hopper', idA);
    await page.getByRole('button', { name: 'Create employee' }).click();
    await expect(page.getByText(/Employee EMP-\d{6} created/)).toBeVisible();
    await expect(page.getByRole('heading', { name: /Lovelace, Ada/i })).toBeVisible();
    const idB = Number(page.url().match(/\/employees\/(\d+)/)![1]);
    expect(idB).toBe(idA + 1);

    await page.goto(`/employees/${idA}`);
    await expect(page.getByRole('heading', { name: /Hopper, Grace/i })).toBeVisible();
    await pickManager(page, 'Lovelace', idB);
    const cycResp = page.waitForResponse((r) => r.url().endsWith(`/api/employees/${idA}`) && r.request().method() === 'PUT');
    await page.getByRole('button', { name: 'Save' }).click();
    const cyc = await cycResp;
    expect(cyc.status()).toBe(400);
    const cycBody = await cyc.json();
    expect(cycBody.code).toBe('-20004');
    expect(cycBody.message).toMatch(/Circular reporting chain detected/);
    await expect(page.getByRole('alert').filter({ hasText: /Circular reporting chain/ })).toBeVisible();

    // --- terminate A via UI ---
    await page.reload();
    await page.getByRole('button', { name: 'Terminate' }).click();
    const terminate = page.getByRole('dialog', { name: 'Terminate employee' });
    await terminate.getByLabel('Effective date *').fill(iso(14));
    await terminate.getByLabel('Reason *').fill('RESIGNED');
    await terminate.getByRole('button', { name: 'Confirm termination' }).click();
    await expect(page.getByText(/terminated$/)).toBeVisible();
    await expect(page.getByTestId('emp-status')).toHaveText('Terminated');
    await expect(page.getByRole('button', { name: 'Terminate' })).toHaveCount(0);
    await page.getByRole('tab', { name: 'Salary' }).click();
    await expect(page.getByText('No active salary record.')).toBeVisible();

    // --- second terminate → -20005 (UI hides the button; via API as the same user) ---
    const token = await apiToken(page, SEED_ACCOUNTS.executive.email);
    const again = await page.request.post(`/api/employees/${idA}/terminate`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { effectiveDate: iso(14), reason: 'RESIGNED' },
    });
    expect(again.status()).toBe(422);
    expect((await again.json()).code).toBe('-20005');

    // --- session revoked on terminate: seed login account jennifer.park (emp 21; emp 12 is owned by Level 2) ---
    const victim = SEED_ACCOUNTS.manager;
    const victimToken = await apiToken(page, victim.email);
    const meBefore = await page.request.get('/api/auth/me', { headers: { Authorization: `Bearer ${victimToken}` } });
    expect(meBefore.status()).toBe(200);
    const termVictim = await page.request.post(`/api/employees/${victim.empId}/terminate`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { effectiveDate: iso(14), reason: 'RESIGNED' },
    });
    expect(termVictim.status(), await termVictim.text()).toBe(200);
    const meAfter = await page.request.get('/api/auth/me', { headers: { Authorization: `Bearer ${victimToken}` } });
    expect(meAfter.status()).toBe(401);
    const relogin = await page.request.post('/api/auth/login', { data: { username: victim.email, password: SEED_PASSWORD } });
    expect(relogin.status()).not.toBe(200);
  });
});
