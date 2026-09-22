import { expect, test, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

const BASE_URL = 'http://localhost:5173';
const PASSWORD = 'Welcome1!';
const evidence: Array<Record<string, unknown>> = [];

function isoDate(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

async function login(page: Page, email: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function loginApi(page: Page, email: string): Promise<string> {
  const response = await page.request.post(`${BASE_URL}/api/auth/login`, {
    data: { username: email, password: PASSWORD },
  });
  const body = await response.json();
  expect(response.ok(), `login failed: ${response.status()} ${JSON.stringify(body)}`).toBeTruthy();
  return body.accessToken as string;
}

test.afterAll(() => {
  const reportDir = '/home/ubuntu/reports';
  mkdirSync(reportDir, { recursive: true });
  writeFileSync(`${reportDir}/stage1-api-evidence.json`, `${JSON.stringify(evidence, null, 2)}\n`);
});

test('NEW_READONLY serves employee reads and rejects backend writes', async ({ page }) => {
  await login(page, 'david.martinez@company.com');
  await page.getByRole('link', { name: 'Employees' }).click();
  await expect(page.getByRole('table', { name: 'Employees' })).toBeVisible();
  await page.getByLabel('Name or number').fill('martinez');
  await page.getByRole('button', { name: 'Search' }).click();
  await page.getByRole('link', { name: 'MARTINEZ, DAVID' }).click();

  await expect(page.getByRole('heading', { name: /MARTINEZ, DAVID/ })).toBeVisible();
  await expect(page.getByTestId('emp-number')).toHaveText('EMP-000011');
  await page.getByRole('tab', { name: 'History' }).click();
  await expect(page.getByRole('heading', { name: 'Employment history' })).toBeVisible();
  await expect(page.getByText('No history recorded.')).toBeVisible();
  evidence.push({ step: 'staff reads detail and history', status: 'passed', empNumber: 'EMP-000011' });

  // The frontend is intentionally configured with employee=NEW for this stage. It therefore
  // does not show the read-only banner even though the backend remains NEW_READONLY.
  await expect(page.getByTestId('read-only-banner')).toHaveCount(0);
  evidence.push({ step: 'frontend flag behavior', status: 'passed', banner: 'not rendered with VITE_MODULE_FLAGS=employee=NEW' });

  await page.getByRole('button', { name: 'Logout' }).click();
  await expect(page).toHaveURL(/\/login/);
  await login(page, 'james.richardson@company.com');
  await page.getByRole('link', { name: 'Employees' }).click();
  await page.getByLabel('Name or number').fill('martinez');
  await page.getByRole('button', { name: 'Search' }).click();
  await page.getByRole('link', { name: 'MARTINEZ, DAVID' }).click();
  await page.getByRole('tab', { name: 'Salary' }).click();
  await expect(page.getByRole('heading', { name: 'Salary', exact: true })).toBeVisible();
  await expect(page.getByTestId('current-salary')).toBeVisible();
  evidence.push({ step: 'executive reads salary tab', status: 'passed' });

  const token = await loginApi(page, 'james.richardson@company.com');
  const response = await page.request.post(`${BASE_URL}/api/employees`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      firstName: 'READONLY',
      lastName: 'PROBE',
      email: `readonly-probe-${Date.now()}@company.com`,
      hireDate: isoDate(7),
      deptId: 20,
      jobId: 53,
      managerEmpId: 10,
      locationCode: 'HQ',
      employmentType: 'FULL_TIME',
      initialSalary: 70000,
    },
  });
  const body = await response.json();
  const result = { step: 'backend POST /api/employees at NEW_READONLY', status: response.status(), code: body.code, body };
  evidence.push(result);
  expect(response.status()).toBe(409);
  expect(body.code).toBe('MODULE_READ_ONLY');
});
