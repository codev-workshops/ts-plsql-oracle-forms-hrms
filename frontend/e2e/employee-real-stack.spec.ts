import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import type { TokenResponse } from '../src/api/types';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

const flag = process.env.VITE_MODULE_FLAGS?.match(/(?:^|[,;])employee=(NEW_READONLY|NEW)(?:$|[,;])/)?.[1];

function iso(offset: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + offset);
  return day.toISOString().slice(0, 10);
}

async function login(page: Page, email: string): Promise<string> {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  const loginResponse = page.waitForResponse((response) => response.url().endsWith('/api/auth/login') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Login' }).click();
  const response = await loginResponse;
  expect(response.ok()).toBe(true);
  await expect(page).not.toHaveURL(/\/login/);
  const session = await response.json() as TokenResponse;
  return session.accessToken;
}

test.describe('P3 PostgreSQL-backed employee flows', () => {
  test.skip(process.env.E2E_REAL_STACK !== '1', 'requires a running PostgreSQL-backed API without MSW');
  test.skip(!flag, 'requires employee=NEW_READONLY or employee=NEW');

  test('read-only search/detail and scoped tabs', async ({ page, request }) => {
    const staffToken = await login(page, SEED_ACCOUNTS.staff.email);
    await page.goto('/employees');
    await expect(page.getByRole('table', { name: 'Employees' })).toBeVisible();
    await page.getByLabel('Name or number').fill(SEED_ACCOUNTS.staff.lastName);
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByRole('link', { name: `${SEED_ACCOUNTS.staff.lastName}, ${SEED_ACCOUNTS.staff.firstName}` }).click();
    await expect(page.getByRole('heading', { name: /MARTINEZ, DAVID/ })).toBeVisible();
    await page.getByRole('tab', { name: 'Salary' }).click();
    await expect(page.getByTestId('current-salary')).toBeVisible();
    await page.getByRole('tab', { name: 'Dependents' }).click();
    await expect(page.getByRole('table', { name: 'Dependents' }).or(page.getByText('No dependents recorded.'))).toBeVisible();
    await page.getByRole('tab', { name: 'Contacts' }).click();
    await expect(page.getByRole('table', { name: 'Emergency contacts' }).or(page.getByText('No emergency contacts recorded.'))).toBeVisible();
    for (const path of ['dependents', 'contacts']) {
      const own = await request.get(`/api/employees/${SEED_ACCOUNTS.staff.empId}/${path}`, {
        headers: { Authorization: `Bearer ${staffToken}` },
      });
      expect(own.status()).toBe(200);
      expect(Array.isArray(await own.json())).toBe(true);
    }
    if (flag === 'NEW_READONLY') {
      await expect(page.getByTestId('read-only-banner')).toBeVisible();
      await expect(page.getByRole('button', { name: 'Add contact' })).toHaveCount(0);
    }

    await page.goto(`/employees/${SEED_ACCOUNTS.manager.empId}/salary`);
    await expect(page.getByRole('alert')).toContainText('not permitted');
    await expect(page.getByRole('tab', { name: 'Salary' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'Dependents' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'Contacts' })).toHaveCount(0);
  });

  test('rejects out-of-scope related reads and invalid JWTs', async ({ page, request }) => {
    const staffToken = await login(page, SEED_ACCOUNTS.staff.email);
    for (const path of ['salary', 'salary/history', 'dependents', 'contacts']) {
      const forbidden = await request.get(`/api/employees/${SEED_ACCOUNTS.manager.empId}/${path}`, {
        headers: { Authorization: `Bearer ${staffToken}` },
        failOnStatusCode: false,
      });
      expect(forbidden.status()).toBe(403);
      expect((await forbidden.json()).code).toBe('FORBIDDEN');
    }
    const unauthenticated = await request.get(`/api/employees/${SEED_ACCOUNTS.staff.empId}/salary`, {
      headers: { Authorization: 'Bearer invalid-token' },
      failOnStatusCode: false,
    });
    expect(unauthenticated.status()).toBe(401);
    expect((await unauthenticated.json()).code).toBe('TOKEN_INVALID');
  });

  test('isolated hire, salary, transfer and termination outside the 2024 baseline', async ({ page, request }) => {
    test.skip(flag !== 'NEW', 'writes require employee=NEW and the salary-module prerequisite');
    test.setTimeout(90_000);
    const token = await login(page, SEED_ACCOUNTS.executive.email);
    const marker = `E2E${randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase()}`;
    const hireDate = iso(7);
    let empId: number | null = null;
    let terminated = false;
    try {
      await page.goto('/employees/new');
      await expect(page.getByRole('heading', { name: 'New employee' })).toBeVisible();
      await page.getByLabel('First name *').fill('P3');
      await page.getByLabel('Last name *').fill(marker);
      await page.getByLabel('E-mail').fill(`${marker.toLowerCase()}@example.com`);
      await page.getByLabel('Hire date *').fill(hireDate);
      await page.getByLabel('Department *').selectOption({ label: 'FIN – Finance' });
      await page.getByLabel('Job title *').selectOption({ label: 'Analyst (G3)' });
      await page.getByLabel('Initial salary').fill('50000');
      await page.getByRole('button', { name: 'Create employee' }).click();
      await expect(page).toHaveURL(/\/employees\/\d+$/);
      empId = Number(new URL(page.url()).pathname.split('/').at(-1));
      await expect(page.getByRole('heading', { name: new RegExp(marker) })).toBeVisible();

      await page.getByRole('button', { name: 'Change salary' }).click();
      const salary = page.getByRole('dialog', { name: 'Change salary' });
      await salary.getByLabel('Effective date *').fill(hireDate);
      await salary.getByLabel('Base salary *').fill('80000');
      await salary.getByLabel('Change reason *').fill('MARKET');
      await salary.getByRole('button', { name: 'Save salary' }).click();
      await expect(page.getByText('Salary set to 80,000.00 USD')).toBeVisible();

      await page.getByRole('button', { name: 'Transfer' }).click();
      const transfer = page.getByRole('dialog', { name: 'Transfer employee' });
      await transfer.getByLabel('Effective date *').fill(hireDate);
      await transfer.getByLabel('New department *').selectOption({ label: 'ENG – Engineering' });
      await transfer.getByRole('button', { name: 'Confirm transfer' }).click();
      await expect(page.getByText(/transferred to Engineering/)).toBeVisible();

      await page.getByRole('tab', { name: 'History' }).click();
      await expect(page.getByRole('table', { name: 'Employment history' })).toContainText('Engineering');

      await page.getByRole('button', { name: 'Terminate' }).click();
      const terminate = page.getByRole('dialog', { name: 'Terminate employee' });
      await terminate.getByLabel('Effective date *').fill(iso(14));
      await terminate.getByLabel('Reason *').fill('RESIGNED');
      await terminate.getByRole('button', { name: 'Confirm termination' }).click();
      await expect(page.getByTestId('emp-status')).toHaveText('Terminated');
      terminated = true;
    } finally {
      if (empId !== null && !terminated) {
        const cleanup = await request.post(`/api/employees/${empId}/terminate`, {
          headers: { Authorization: `Bearer ${token}` },
          data: { effectiveDate: iso(14), reason: 'RESIGNED' },
          failOnStatusCode: false,
        });
        expect(cleanup.status(), `Could not terminate isolated test employee ${empId}`).toBe(200);
      }
    }
  });
});
