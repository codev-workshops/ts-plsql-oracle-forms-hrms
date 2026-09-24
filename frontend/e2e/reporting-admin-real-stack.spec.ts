import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import type { AuditLogPage, EmployeeDirectoryPage, Holiday, Role, TokenResponse } from '../src/api/types';
import { SEED_ACCOUNTS, SEED_PASSWORD } from './seed-accounts';

/**
 * P5 reporting + HRMS_ADMIN golden paths against the real stack (no MSW): Vite dev server →
 * proxy → Java reporting/admin services → PostgreSQL loaded with `tools/fixtures/pg/*.sql`.
 *
 * Prerequisites (the integration session / testing agent owns bringing these up):
 *   - PostgreSQL with the P0 fixtures and the P5 Flyway migrations (HOLIDAYS, PAY_ELEMENTS,
 *     TAX_BRACKETS, ROLES / USER_ROLES).
 *   - auth-service + reporting/admin services reachable through the Vite proxy with the local
 *     implementation-validation flag `reporting=NEW` (also exported as VITE_MODULE_FLAGS).
 *   - seed accounts from ./seed-accounts.ts with password `Welcome1!`.
 *
 *   E2E_REAL_STACK=1 VITE_MODULE_FLAGS=reporting=NEW npx playwright test reporting-admin-real-stack
 *
 * Every UI assertion is reconciled against the same API the browser hit, so a mock-only pass is
 * impossible. Isolation: the read paths only read seeded data; the write paths create their own
 * holiday (unique generated name + date) and role (unique generated code) and always clean them up
 * (UI, then API fallback). The seeded accounts' roles / status are never modified because the
 * real-stack project runs all specs against the same three shared accounts.
 *
 * This is local implementation validation only — it is not evidence of the Forms decommission,
 * the 30-day zero-hit window, or any Oracle / CDC / live-SSO behaviour.
 */

const flag = process.env.VITE_MODULE_FLAGS?.match(/(?:^|[,;])reporting=(\w+)(?:$|[,;])/)?.[1];
const RUN_ID = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`.toUpperCase();

async function login(page: Page, email: string): Promise<string> {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(SEED_PASSWORD);
  const loginResponse = page.waitForResponse((r) => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Login' }).click();
  const response = await loginResponse;
  expect(response.ok()).toBe(true);
  await expect(page).not.toHaveURL(/\/login/);
  return ((await response.json()) as TokenResponse).accessToken;
}

const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });

async function getJson<T>(request: APIRequestContext, token: string, url: string): Promise<T> {
  const response = await request.get(url, { headers: bearer(token) });
  expect(response.status(), url).toBe(200);
  return (await response.json()) as T;
}

/** JWT `sub` (user-account id) of an access token; the canonical P5 audit actor. */
function jwtSub(token: string): string {
  const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')) as { sub: string };
  return payload.sub;
}

/** Picks a January date next year with no active company-wide holiday (active or not, any location, is listed; only active company-wide rows collide). */
async function freeHolidayDate(request: APIRequestContext, token: string): Promise<string> {
  const year = new Date().getUTCFullYear() + 1;
  const rows = await getJson<Holiday[]>(request, token, `/api/admin/holidays?year=${year}`);
  const taken = new Set(rows.filter((h) => h.activeFlag && h.locationCode == null).map((h) => h.holidayDate));
  for (let day = 2; day <= 28; day += 1) {
    const iso = `${year}-01-${String(day).padStart(2, '0')}`;
    if (!taken.has(iso)) return iso;
  }
  throw new Error(`no free company-wide holiday date in January ${year}`);
}

test.describe('P5 PostgreSQL-backed reporting + admin flows', () => {
  test.skip(process.env.E2E_REAL_STACK !== '1', 'requires a running PostgreSQL-backed API without MSW');
  test.skip(flag !== 'NEW', 'requires VITE_MODULE_FLAGS=reporting=NEW on the proxy (local implementation validation only)');

  test('manager (REPORTS:VIEW + ADMIN:VIEW) queries and exports the directory; admin writes are refused', async ({ page, request }) => {
    const token = await login(page, SEED_ACCOUNTS.manager.email);
    const all = await getJson<EmployeeDirectoryPage>(request, token, '/api/reports/employee-directory?size=200');
    expect(all.summary.totalHeadcount).toBeGreaterThan(1);
    const dept = all.summary.headcountByDepartment.find((d) => d.headcount < all.summary.totalHeadcount);
    expect(dept, 'fixtures need at least two departments with active employees').toBeTruthy();
    const deptId = all.content.find((r) => r.deptCode === dept!.deptCode)!.deptId;

    await page.getByRole('link', { name: 'Reports' }).click();
    const table = page.getByRole('table', { name: 'Employee Directory' });
    await expect(table).toBeVisible();
    await expect(table.getByRole('row')).toHaveCount(Math.min(all.page.totalElements, all.page.size) + 1);

    const filtered = page.waitForResponse((r) => r.url().includes('/api/reports/employee-directory') && r.url().includes(`deptId=${deptId}`) && r.request().method() === 'GET');
    await page.getByLabel('Department').selectOption(String(deptId));
    await page.getByRole('button', { name: 'Run report' }).click();
    const filteredBody = (await (await filtered).json()) as EmployeeDirectoryPage;
    expect(filteredBody.page.totalElements).toBe(dept!.headcount);
    await expect(table.getByRole('row')).toHaveCount(filteredBody.content.length + 1);
    for (const row of filteredBody.content) await expect(table.getByRole('row', { name: new RegExp(row.empNumber) })).toBeVisible();

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export CSV' }).click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/^employee-directory.*\.csv$/);
    const csv = (await (await file.createReadStream()).toArray()).join('');
    const lines = csv.trim().split(/\r?\n/);
    expect(lines[0]).toMatch(/^empId,empNumber,firstName,lastName,fullName/);
    expect(lines.length - 1).toBe(dept!.headcount);
    const serverCsv = await request.get(`/api/reports/employee-directory.csv?deptId=${deptId}`, { headers: bearer(token) });
    expect(serverCsv.status()).toBe(200);
    expect((await serverCsv.text()).trim()).toBe(csv.trim());

    await page.getByRole('link', { name: 'Administration' }).click();
    await page.getByRole('tab', { name: 'Holidays' }).click();
    await expect(page.getByRole('table', { name: 'Holidays' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New holiday' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Deactivate/ })).toHaveCount(0);
    await page.getByRole('tab', { name: 'Users' }).click();
    await expect(page.getByRole('table', { name: 'User accounts' })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Assign roles/ })).toHaveCount(0);
    const refused = await request.post('/api/admin/holidays', {
      headers: bearer(token),
      data: { holidayDate: '2099-01-01', holidayName: `REFUSED-${RUN_ID}`, floatingFlag: false, activeFlag: true },
      failOnStatusCode: false,
    });
    expect(refused.status()).toBe(403);
  });

  test('executive (ADMIN:EDIT) creates, edits, deactivates a holiday and finds it in the audit log', async ({ page, request }) => {
    const token = await login(page, SEED_ACCOUNTS.executive.email);
    const date = await freeHolidayDate(request, token);
    const name = `E2E ${RUN_ID}`;
    let created: Holiday | undefined;
    try {
      await page.getByRole('link', { name: 'Administration' }).click();
      await page.getByRole('tab', { name: 'Holidays' }).click();
      await expect(page.getByRole('table', { name: 'Holidays' })).toBeVisible();
      await page.getByRole('button', { name: 'New holiday' }).click();
      const dialog = page.getByRole('dialog', { name: 'New holiday' });
      await dialog.getByLabel(/^Date/).fill(date);
      await dialog.getByLabel(/^Name/).fill(name);
      const post = page.waitForResponse((r) => r.url().endsWith('/api/admin/holidays') && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: 'Create' }).click();
      const postResponse = await post;
      expect(postResponse.status()).toBe(201);
      created = (await postResponse.json()) as Holiday;
      expect(postResponse.request().postDataJSON()).not.toHaveProperty('createdBy');
      const row = page.getByRole('row', { name: new RegExp(name) });
      await expect(row).toBeVisible();
      await expect(row).toContainText('Company-wide');

      await row.getByRole('button', { name: /^Edit/ }).click();
      const edit = page.getByRole('dialog', { name: /^Edit/ });
      await edit.getByLabel(/^Name/).fill(`${name} renamed`);
      await edit.getByRole('button', { name: 'Save' }).click();
      await expect(page.getByRole('row', { name: new RegExp(`${name} renamed`) })).toBeVisible();
      const afterEdit = await getJson<Holiday>(request, token, `/api/admin/holidays/${created.holidayId}`);
      expect(afterEdit.holidayName).toBe(`${name} renamed`);
      expect(afterEdit.modifiedBy, 'HOLIDAYS has no modified_by column').toBeNull();
      expect(afterEdit.modifiedDate).toBeNull();

      await page.getByRole('row', { name: new RegExp(`${name} renamed`) }).getByRole('button', { name: /^Deactivate/ }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Deactivate' }).click();
      await expect(page.getByRole('row', { name: new RegExp(name) })).toBeHidden();
      const inactive = await getJson<Holiday>(request, token, `/api/admin/holidays/${created.holidayId}`);
      expect(inactive.activeFlag).toBe(false);

      await page.getByRole('tab', { name: 'Audit log' }).click();
      // AuditLogSearchQuery.tableName is the lower-case PostgreSQL table name (^[a-z][a-z0-9_]*$);
      // the server compares lower(table_name) and echoes the stored upper-case name.
      const holidayId = created.holidayId;
      await page.getByLabel(/^Table/).fill('holidays');
      await page.getByLabel(/^Record id/).fill(String(holidayId));
      const audit = page.waitForResponse((r) => r.url().includes('/api/admin/audit-log') && r.url().includes('tableName=holidays') && r.url().includes(`recordId=${holidayId}`) && r.request().method() === 'GET');
      await page.getByRole('button', { name: 'Search' }).click();
      const auditResponse = await audit;
      expect(auditResponse.status()).toBe(200);
      const auditPage = (await auditResponse.json()) as AuditLogPage;
      expect(auditPage.content.length).toBeGreaterThanOrEqual(2);
      const actions = auditPage.content.map((a) => a.actionType);
      expect(actions).toEqual(expect.arrayContaining(['INSERT', 'UPDATE']));
      const actor = jwtSub(token);
      for (const entry of auditPage.content) {
        expect(entry.tableName.toLowerCase()).toBe('holidays');
        expect(entry.recordId).toBe(holidayId);
        expect(entry.changedBy, 'audit actor is the JWT sub (user-account id)').toBe(actor);
      }
      const auditTable = page.getByRole('table', { name: 'Audit log' });
      await expect(auditTable.getByRole('row')).toHaveCount(auditPage.content.length + 1);
      for (const entry of auditPage.content) await expect(auditTable.getByRole('row', { name: new RegExp(`^${entry.auditId}\\b`) })).toContainText(actor);
    } finally {
      if (created && (await request.get(`/api/admin/holidays/${created.holidayId}`, { headers: bearer(token) })).ok()) {
        await request.delete(`/api/admin/holidays/${created.holidayId}`, { headers: bearer(token), failOnStatusCode: false });
      }
    }
  });

  test('executive creates a role with validated permissions, sees it in the users multi-select, then deletes it', async ({ page, request }) => {
    const token = await login(page, SEED_ACCOUNTS.executive.email);
    const code = `E2E_${RUN_ID}`;
    let roleId: number | undefined;
    try {
      await page.getByRole('link', { name: 'Administration' }).click();
      await page.getByRole('tab', { name: 'Roles' }).click();
      await expect(page.getByRole('table', { name: 'Roles' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Edit EXECUTIVE' })).toBeDisabled();

      await page.getByRole('button', { name: 'New role' }).click();
      const dialog = page.getByRole('dialog', { name: 'New role' });
      await dialog.getByLabel(/^Code/).fill(code);
      await dialog.getByLabel(/^Name/).fill(`E2E role ${RUN_ID}`);
      await dialog.getByLabel(/^Minimum grade/).fill('1');
      await dialog.getByLabel(/^Maximum grade/).fill('3');
      await dialog.getByRole('button', { name: 'Create' }).click();
      await expect(dialog.getByRole('alert')).toContainText(/at least one permission/i);
      await dialog.getByRole('checkbox', { name: 'REPORTS:VIEW' }).check();
      await dialog.getByRole('checkbox', { name: 'ADMIN:VIEW' }).check();
      const post = page.waitForResponse((r) => r.url().endsWith('/api/admin/roles') && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: 'Create' }).click();
      const postResponse = await post;
      expect(postResponse.status()).toBe(201);
      const created = (await postResponse.json()) as Role;
      roleId = created.roleId;
      expect([...created.permissions].sort()).toEqual(['ADMIN:VIEW', 'REPORTS:VIEW']);
      const row = page.getByRole('row', { name: new RegExp(code) });
      await expect(row).toBeVisible();
      await expect(row.getByRole('list', { name: `Permissions of ${code}` })).toContainText('REPORTS:VIEW');

      await page.getByRole('tab', { name: 'Users' }).click();
      const users = page.getByRole('table', { name: 'User accounts' });
      await expect(users).toBeVisible();
      const managerRow = users.getByRole('row', { name: new RegExp(SEED_ACCOUNTS.manager.email) });
      await expect(managerRow.getByRole('list', { name: /Effective authorities/ })).toContainText('ADMIN:VIEW');
      await expect(managerRow.getByRole('list', { name: /Effective authorities/ })).not.toContainText('ADMIN:EDIT');
      await expect(page.getByRole('button', { name: `Assign roles to ${SEED_ACCOUNTS.executive.email}` })).toBeDisabled();
      await managerRow.getByRole('button', { name: /^Assign roles/ }).click();
      const assign = page.getByRole('dialog', { name: /^Roles for/ });
      await expect(assign.getByRole('checkbox', { name: new RegExp(`^${code}`) })).toBeVisible();
      await assign.getByRole('button', { name: 'Cancel' }).click();

      await page.getByRole('tab', { name: 'Roles' }).click();
      await page.getByRole('button', { name: `Delete ${code}` }).click();
      const del = page.waitForResponse((r) => r.url().endsWith(`/api/admin/roles/${roleId}`) && r.request().method() === 'DELETE');
      await page.getByRole('dialog', { name: `Delete role ${code}?` }).getByRole('button', { name: 'Delete' }).click();
      expect((await del).status()).toBe(204);
      await expect(page.getByRole('row', { name: new RegExp(code) })).toBeHidden();
      const gone = await request.get(`/api/admin/roles/${roleId}`, { headers: bearer(token), failOnStatusCode: false });
      expect(gone.status()).toBe(404);
    } finally {
      if (roleId !== undefined) await request.delete(`/api/admin/roles/${roleId}`, { headers: bearer(token), failOnStatusCode: false });
    }
  });
});
