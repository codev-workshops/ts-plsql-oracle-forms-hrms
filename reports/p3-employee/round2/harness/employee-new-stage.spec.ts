import { expect, test, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

const BASE_URL = 'http://localhost:5173';
const PASSWORD = 'Welcome1!';
const evidence: Array<Record<string, unknown>> = [];

function isoDate(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

function dbScalar(sql: string): string {
  return execFileSync(
    'docker',
    ['exec', 'hrms-pg', 'psql', '-U', 'hrms', '-d', 'hrms', '-Atc', sql],
    { encoding: 'utf8' },
  ).trim();
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
  writeFileSync(`${reportDir}/stage2-api-evidence.json`, `${JSON.stringify(evidence, null, 2)}\n`);
});

test('NEW supports employee lifecycle, salary history, and contract errors', async ({ page }) => {
  await login(page, 'james.richardson@company.com');
  await page.getByRole('link', { name: 'Employees' }).click();
  await expect(page.getByRole('table', { name: 'Employees' })).toBeVisible();

  const previousMax = Number(dbScalar(
    "select coalesce(max(cast(substring(emp_number from '[0-9]+$') as integer)), 0) from employees",
  ));
  const uniqueEmail = `real-stack-${Date.now()}@company.com`;
  await page.getByRole('button', { name: 'New employee' }).click();
  await page.getByLabel(/^First name/).fill('REAL');
  await page.getByLabel(/^Last name/).fill('STACK');
  await page.getByLabel(/^E-mail/).fill(uniqueEmail);
  await page.getByLabel(/^Hire date/).fill(isoDate(7));
  await page.getByLabel(/^Department/).selectOption({ label: 'FIN – Finance & Accounting' });
  await page.getByLabel(/^Job title/).selectOption({ label: 'QA Analyst (G3)' });
  await page.getByLabel(/^Initial salary/).fill('70000');

  const [createResponse] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith('/api/employees') && r.request().method() === 'POST'),
    page.getByRole('button', { name: 'Create employee' }).click(),
  ]);
  const created = await createResponse.json();
  evidence.push({ step: 'create employee', status: createResponse.status(), empNumber: created.empNumber, id: created.id });
  expect(createResponse.status()).toBe(201);
  await expect(page.getByText(new RegExp(`Employee ${created.empNumber} created`))).toBeVisible();
  await expect(page.getByRole('heading', { name: /STACK, REAL/ })).toBeVisible();

  const employeeId = Number(created.id);
  const expectedNumber = `EMP-${String(previousMax + 1).padStart(6, '0')}`;
  expect(created.empNumber).toBe(expectedNumber);
  const activeAfterCreate = Number(dbScalar(
    `select count(*) from salary_records where emp_id = ${employeeId} and active_flag = 'Y'`,
  ));
  expect(activeAfterCreate).toBe(1);
  evidence.push({ step: 'sequence and initial salary', status: 'passed', expectedNumber, activeSalaryRows: activeAfterCreate });

  await page.getByRole('button', { name: 'Change salary' }).click();
  const salaryDialog = page.getByRole('dialog', { name: 'Change salary' });
  await salaryDialog.getByLabel(/^Effective date/).fill(isoDate(8));
  await salaryDialog.getByLabel(/^Base salary/).fill('80000');
  await salaryDialog.getByLabel(/^Change reason/).fill('MARKET');
  const [salaryResponse] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`/api/employees/${employeeId}/salary`) && r.request().method() === 'POST'),
    salaryDialog.getByRole('button', { name: 'Save salary' }).click(),
  ]);
  const salaryBody = await salaryResponse.json();
  evidence.push({ step: 'salary change', status: salaryResponse.status(), body: salaryBody });
  expect(salaryResponse.status()).toBe(201);
  await expect(page.getByText('Salary set to 80,000.00 USD')).toBeVisible();
  const salaryCounts = dbScalar(
    `select count(*) filter (where active_flag = 'Y') || '|' || count(*) filter (where active_flag = 'N' and end_date is not null) from salary_records where emp_id = ${employeeId}`,
  ).split('|').map(Number);
  expect(salaryCounts).toEqual([1, 1]);
  evidence.push({ step: 'salary DB close/open rows', status: 'passed', activeRows: salaryCounts[0], closedRowsWithEndDate: salaryCounts[1] });

  const duplicateResponsePromise = page.waitForResponse(
    (r) => r.url().endsWith(`/api/employees/${employeeId}`) && r.request().method() === 'PUT',
  );
  await page.getByLabel(/^E-mail/).fill('david.martinez@company.com');
  await page.getByRole('button', { name: 'Save' }).click();
  const duplicateResponse = await duplicateResponsePromise;
  const duplicateBody = await duplicateResponse.json();
  const duplicateResult = {
    step: 'duplicate active e-mail',
    status: duplicateResponse.status(),
    code: duplicateBody.code,
    body: duplicateBody,
  };
  evidence.push(duplicateResult);
  expect(duplicateResponse.status()).toBe(409);
  expect(duplicateBody.code).toBe('-20502');
  await expect(page.getByRole('alert').filter({ hasText: /Email address already in use/ })).toBeVisible();
  evidence.push({ step: 'duplicate e-mail UI rendering', status: 'passed', distinction: 'API returned contract code and UI rendered inline field error' });

  const token = await loginApi(page, 'james.richardson@company.com');
  const detailResponse = await page.request.get(`${BASE_URL}/api/employees/${employeeId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const detail = await detailResponse.json();
  const etag = detailResponse.headers().etag ?? `"${detail.version}"`;
  const cycleResponse = await page.request.put(`${BASE_URL}/api/employees/${employeeId}`, {
    headers: { Authorization: `Bearer ${token}`, 'If-Match': etag },
    data: {
      firstName: detail.firstName,
      lastName: detail.lastName,
      email: detail.email,
      jobId: detail.jobId,
      managerEmpId: employeeId,
      employmentType: detail.employmentType,
    },
  });
  const cycleBody = await cycleResponse.json();
  evidence.push({ step: 'self-manager cycle', status: cycleResponse.status(), code: cycleBody.code, body: cycleBody });
  expect(cycleResponse.status()).toBe(400);
  expect(cycleBody.code).toBe('-20004');

  await page.getByRole('button', { name: 'Terminate' }).click();
  const terminateDialog = page.getByRole('dialog', { name: 'Terminate employee' });
  await terminateDialog.getByLabel(/^Effective date/).fill(isoDate(30));
  await terminateDialog.getByLabel(/^Reason/).fill('VOLUNTARY');
  const [terminateResponse] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`/api/employees/${employeeId}/terminate`) && r.request().method() === 'POST'),
    terminateDialog.getByRole('button', { name: 'Confirm termination' }).click(),
  ]);
  const terminatedBody = await terminateResponse.json();
  evidence.push({ step: 'terminate employee', status: terminateResponse.status(), body: terminatedBody });
  expect(terminateResponse.status()).toBe(200);
  await expect(page.getByText(new RegExp(`Employee ${created.empNumber} terminated$`))).toBeVisible();
  await expect(page.getByTestId('emp-status')).toHaveText('Terminated');
  await expect(page.getByRole('button', { name: 'Terminate' })).toHaveCount(0);
  expect(Number(dbScalar(
    `select count(*) from salary_records where emp_id = ${employeeId} and active_flag = 'Y'`,
  ))).toBe(0);

  const secondTerminateResponse = await page.request.post(`${BASE_URL}/api/employees/${employeeId}/terminate`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { effectiveDate: isoDate(31), reason: 'VOLUNTARY' },
  });
  const secondTerminateBody = await secondTerminateResponse.json();
  evidence.push({
    step: 'second termination',
    status: secondTerminateResponse.status(),
    code: secondTerminateBody.code,
    body: secondTerminateBody,
  });
  expect(secondTerminateResponse.status()).toBe(422);
  expect(secondTerminateBody.code).toBe('-20005');

  const accountCount = Number(dbScalar(`select count(*) from user_accounts where emp_id = ${employeeId}`));
  expect(accountCount).toBe(0);
  evidence.push({
    step: 'session revocation',
    status: 'not exercised',
    reason: 'Fresh employee has no user_account; seeded accounts are referenced by tests/golden/*.csv and were not terminated',
    userAccountRows: accountCount,
  });
});
