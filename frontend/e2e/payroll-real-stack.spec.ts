import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import type { PayrollDetail, PayrollRun, PayrollRunStatus, TokenResponse } from '../src/api/types';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * P4 payroll golden path against the real stack (no MSW): Vite dev server → proxy → Java
 * `payroll-service` → PostgreSQL loaded with `tools/fixtures/pg/*.sql`.
 *
 * Prerequisites (the integration session / testing agent owns bringing these up):
 *   - PostgreSQL with the P0 fixtures: pay periods 202405 (MAY-2024, CLOSED) and 202406
 *     (JUN-2024, OPEN); runs 9001 (APPROVED, 4 employees, 1 ERROR row) and 9002 (CALCULATED).
 *   - auth-service + payroll-service reachable through the Vite proxy with the local
 *     implementation-validation flags `payroll=NEW,payroll.engine=JAVA`
 *     (also exported as VITE_MODULE_FLAGS so the spec knows the tile is promoted).
 *   - seed accounts from ./seed-accounts.ts with password `Welcome1!`.
 *
 *   E2E_REAL_STACK=1 VITE_MODULE_FLAGS=payroll=NEW,payroll.engine=JAVA npx playwright test payroll-real-stack
 *
 * Isolation: the read-only test only touches the seeded, already-approved run 9001. The write
 * test creates its own run in the OPEN period 202406 and always reverses it (UI, then API
 * fallback) so the period returns to OPEN and `vw_payroll_latest` (max APPROVED run) is left
 * unchanged. A run that never left PENDING is not reversible per the contract; that case is
 * reported as a test failure rather than silently ignored.
 *
 * This is local implementation validation only — it is not evidence of shadow-gate promotion,
 * three production periods, or any Oracle / Forms / CDC / live-SSO behaviour.
 */

const flag = process.env.VITE_MODULE_FLAGS?.match(/(?:^|[,;])payroll=(\w+)(?:$|[,;])/)?.[1];

const SEEDED = { closedPeriod: 202405, openPeriod: 202406, approvedRun: 9001, errorEmpId: 31, payslipEmpId: 21 } as const;
const REVERSIBLE = new Set(['CALCULATED', 'APPROVED', 'PAID', 'ERROR']);

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

const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });

async function runsForPeriod(request: APIRequestContext, token: string, periodId: number): Promise<PayrollRun[]> {
  const response = await request.get(`/api/payroll/periods/${periodId}/runs`, { headers: bearer(token) });
  expect(response.status()).toBe(200);
  return await response.json() as PayrollRun[];
}

async function reverseRun(request: APIRequestContext, token: string, runId: number) {
  const status = await request.get(`/api/payroll/runs/${runId}/status`, { headers: bearer(token) });
  if (!status.ok()) return;
  const current = (await status.json() as PayrollRunStatus).status;
  if (current === 'REVERSED') return;
  expect(REVERSIBLE.has(current), `Isolated run ${runId} left in non-reversible status ${current}; reverse it manually`).toBe(true);
  const reversed = await request.post(`/api/payroll/runs/${runId}/reverse`, {
    headers: bearer(token),
    data: { reason: 'payroll-real-stack.spec cleanup' },
    failOnStatusCode: false,
  });
  expect(reversed.status(), `Could not reverse isolated payroll run ${runId}`).toBe(200);
}

test.describe('P4 PostgreSQL-backed payroll flows', () => {
  test.skip(process.env.E2E_REAL_STACK !== '1', 'requires a running PostgreSQL-backed API without MSW');
  test.skip(flag !== 'NEW', 'requires VITE_MODULE_FLAGS=payroll=NEW,payroll.engine=JAVA on the proxy (local implementation validation only)');

  test('manager (PAYROLL:VIEW) reads seeded periods, the approved run, its ERROR row and a payslip; writes are refused', async ({ page, request }) => {
    const token = await login(page, SEED_ACCOUNTS.manager.email);
    await page.getByRole('link', { name: 'Payroll' }).click();
    await expect(page.getByRole('table', { name: 'Pay periods' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Close period' })).toHaveCount(0);
    await expect(page.getByTestId(`period-row-${SEEDED.closedPeriod}`)).toContainText('Closed');
    await expect(page.getByTestId(`period-row-${SEEDED.openPeriod}`)).toBeVisible();

    await page.getByTestId(`period-runs-${SEEDED.closedPeriod}`).click();
    await expect(page.getByRole('table', { name: 'Payroll runs' })).toBeVisible();
    await expect(page.getByTestId('runs-period-name')).toHaveText('MAY-2024');
    const row = page.getByTestId(`run-row-${SEEDED.approvedRun}`);
    await expect(row.locator('.badge').first()).toHaveText('Approved');
    await expect(page.getByTestId(`run-errors-${SEEDED.approvedRun}`)).toHaveText('1 error');
    await expect(page.getByTestId('create-run')).toHaveCount(0);
    await expect(page.getByTestId(`run-approve-${SEEDED.approvedRun}`)).toHaveCount(0);
    await expect(page.getByTestId(`run-reverse-${SEEDED.approvedRun}`)).toHaveCount(0);
    await expect(page.getByLabel(/include bank/)).toHaveCount(0);

    await page.getByTestId(`run-details-${SEEDED.approvedRun}`).click();
    await expect(page.getByRole('table', { name: 'Pay details' })).toBeVisible();
    await expect(page.getByTestId('detail-error-banner')).toContainText('1 employee');
    await expect(page.locator('tr.detail-error')).toContainText('HSA election missing for 2024');
    await page.getByLabel('Status').selectOption('ERROR');
    await page.getByRole('button', { name: 'Apply' }).click();
    await expect(page.locator('[data-testid^="detail-row-"]')).toHaveCount(1);

    await page.goto(`/payroll/runs/${SEEDED.approvedRun}/details`);
    await page.getByTestId(`payslip-link-${SEEDED.payslipEmpId}`).first().click();
    await expect(page.getByTestId('payslip')).toContainText('JENNIFER PARK');
    await expect(page.getByTestId('payslip-gross')).toHaveText('9,166.67');
    await expect(page.getByTestId('payslip-net')).toHaveText('5,512.09');

    const errorRows = await request.get(`/api/payroll/runs/${SEEDED.approvedRun}/details?empId=${SEEDED.errorEmpId}&status=ERROR`, { headers: bearer(token) });
    const [errorRow] = (await errorRows.json() as { content: PayrollDetail[] }).content;
    expect(errorRow?.errorCode).toBeTruthy();
    const errorSlip = await request.get(`/api/payroll/runs/${SEEDED.approvedRun}/payslips/${SEEDED.errorEmpId}`, { headers: bearer(token), failOnStatusCode: false });
    expect(errorSlip.status()).toBe(422);
    expect((await errorSlip.json() as { code: string }).code).toBe(errorRow.errorCode);
    await page.goto(`/payroll/runs/${SEEDED.approvedRun}/payslips/${SEEDED.errorEmpId}`);
    await expect(page.getByTestId('payslip-error')).toContainText(errorRow.errorCode!);

    const create = await request.post(`/api/payroll/periods/${SEEDED.openPeriod}/runs`, { headers: bearer(token), data: { runType: 'REGULAR' }, failOnStatusCode: false });
    expect(create.status()).toBe(403);
    const bank = await request.get(`/api/payroll/runs/${SEEDED.approvedRun}/register.csv?includeBank=true`, { headers: bearer(token), failOnStatusCode: false });
    expect(bank.status()).toBe(403);
    const register = await request.get(`/api/payroll/runs/${SEEDED.approvedRun}/register.csv`, { headers: bearer(token) });
    expect(register.status()).toBe(200);
    expect(register.headers()['content-disposition']).toMatch(/PAY_REGISTER_9001_\d{8}_\d{6}\.csv/);
    expect((await register.text()).split('\n')[0]).not.toMatch(/BANK/i);
  });

  test('staff without PAYROLL:VIEW never sees the tile or the API', async ({ page, request }) => {
    const token = await login(page, SEED_ACCOUNTS.staff.email);
    await expect(page.getByTestId('tile-payroll')).toHaveCount(0);
    await page.goto('/payroll');
    await expect(page.getByRole('table', { name: 'Pay periods' })).toHaveCount(0);
    const periods = await request.get('/api/payroll/periods', { headers: bearer(token), failOnStatusCode: false });
    expect(periods.status()).toBe(403);
    const ownSlip = await request.get(`/api/payroll/runs/${SEEDED.approvedRun}/payslips/${SEED_ACCOUNTS.staff.empId}`, { headers: bearer(token), failOnStatusCode: false });
    expect([200, 404, 422]).toContain(ownSlip.status());
  });

  test('executive (PAYROLL:APPROVE) creates, calculates (202 + /status polling), inspects details/payslip, approves, downloads the register and reverses an isolated run', async ({ page, request }) => {
    test.setTimeout(180_000);
    const token = await login(page, SEED_ACCOUNTS.executive.email);
    const before = new Set((await runsForPeriod(request, token, SEEDED.openPeriod)).map((r) => r.runId));
    let runId: number | null = null;
    let reversed = false;
    try {
      await page.goto(`/payroll/periods/${SEEDED.openPeriod}/runs`);
      await expect(page.getByRole('table', { name: 'Payroll runs' })).toBeVisible();
      await expect(page.getByTestId('runs-period-name')).toHaveText('JUN-2024');

      await page.getByTestId('create-run').click();
      const dialog = page.getByRole('dialog', { name: 'Create payroll run' });
      await dialog.getByLabel(/Run type/).selectOption('REGULAR');
      const created = page.waitForResponse((r) => /\/api\/payroll\/periods\/\d+\/runs$/.test(r.url()) && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: 'Create run' }).click();
      const createResponse = await created;
      expect(createResponse.status()).toBe(201);
      const run = await createResponse.json() as PayrollRun;
      runId = run.runId;
      expect(before.has(runId)).toBe(false);
      expect(run.status).toBe('PENDING');
      await expect(dialog).toHaveCount(0);
      const row = page.getByTestId(`run-row-${runId}`);
      await expect(row.locator('.badge').first()).toHaveText('Pending');
      await expect(page.getByTestId(`run-reverse-${runId}`)).toHaveCount(0);

      const calculated = page.waitForResponse((r) => r.url().endsWith(`/api/payroll/runs/${runId}/calculate`));
      const polled = page.waitForResponse((r) => r.url().endsWith(`/api/payroll/runs/${runId}/status`));
      await row.getByRole('button', { name: 'Calculate' }).click();
      expect((await calculated).status()).toBe(202);
      expect((await polled).status()).toBe(200);
      await expect(row.locator('.badge').first()).toHaveText(/Calculated|Error/, { timeout: 60_000 });
      const status = await request.get(`/api/payroll/runs/${runId}/status`, { headers: bearer(token) });
      const final = await status.json() as PayrollRunStatus;
      expect(['CALCULATED', 'ERROR']).toContain(final.status);
      expect(final.finishedAt).not.toBeNull();
      await expect(page.getByTestId(`run-reverse-${runId}`)).toBeVisible();

      await page.getByTestId(`run-details-${runId}`).click();
      await expect(page.getByRole('table', { name: 'Pay details' })).toBeVisible();
      const detailsPage = await request.get(`/api/payroll/runs/${runId}/details?size=500`, { headers: bearer(token) });
      const details = (await detailsPage.json() as { content: PayrollDetail[] }).content;
      expect(details.length).toBeGreaterThan(0);
      const errorRows = details.filter((d) => d.status === 'ERROR');
      expect(errorRows.length).toBe(final.errorCount);
      if (errorRows.length > 0) await expect(page.getByTestId('detail-error-banner')).toBeVisible();
      const earning = details.find((d) => d.status === 'CALCULATED' && d.elementType === 'EARNING');

      if (final.status === 'CALCULATED') {
        if (earning) {
          await page.getByTestId(`payslip-link-${earning.empId}`).first().click();
          await expect(page.getByTestId('payslip')).toBeVisible();
          await expect(page.getByTestId('payslip-gross')).toHaveText(/\d/);
          await expect(page.getByTestId('payslip-net')).toHaveText(/\d/);
          await page.goto(`/payroll/periods/${SEEDED.openPeriod}/runs`);
        }
        await page.getByTestId(`run-approve-${runId}`).click();
        await page.getByTestId('confirm-approve').click();
        const result = page.getByTestId('approval-result');
        await expect(result).toContainText(`Run #${runId} approved`);
        await result.getByRole('button', { name: 'Close' }).click();
        await expect(row.locator('.badge').first()).toHaveText('Approved');

        await page.getByLabel(/include bank/).check();
        const download = page.waitForEvent('download');
        await page.getByTestId(`run-register-${runId}`).click();
        expect((await download).suggestedFilename()).toMatch(new RegExp(`^PAY_REGISTER_${runId}_\\d{8}_\\d{6}\\.csv$`));
        const register = await request.get(`/api/payroll/runs/${runId}/register.csv?includeBank=true`, { headers: bearer(token) });
        expect(register.status()).toBe(200);
        expect((await register.text()).split('\n')[0]).toMatch(/BANK/i);
      }

      await page.getByTestId(`run-reverse-${runId}`).click();
      const reverseDialog = page.getByRole('dialog', { name: `Reverse payroll run #${runId}` });
      await reverseDialog.getByRole('button', { name: 'Reverse run' }).click();
      await expect(reverseDialog.getByRole('alert')).toContainText('required');
      await reverseDialog.getByLabel(/Reason/).fill('payroll-real-stack.spec isolated run');
      const reverseResponse = page.waitForResponse((r) => r.url().endsWith(`/api/payroll/runs/${runId}/reverse`));
      await reverseDialog.getByRole('button', { name: 'Reverse run' }).click();
      reversed = (await reverseResponse).ok();
      expect(reversed).toBe(true);
      await expect(row.locator('.badge').first()).toHaveText('Reversed');
      await expect(page.getByTestId(`run-reverse-${runId}`)).toHaveCount(0);

      const after = await runsForPeriod(request, token, SEEDED.openPeriod);
      expect(after.find((r) => r.runId === runId)?.status).toBe('REVERSED');
      const periods = await request.get(`/api/payroll/periods?status=OPEN&size=100`, { headers: bearer(token) });
      expect((await periods.json() as { content: { periodId: number }[] }).content.map((p) => p.periodId)).toContain(SEEDED.openPeriod);
    } finally {
      if (runId !== null && !reversed) await reverseRun(request, token, runId);
    }
  });
});
