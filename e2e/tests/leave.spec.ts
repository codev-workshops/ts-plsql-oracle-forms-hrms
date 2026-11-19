import { expect, test } from '@playwright/test';
import { EMPLOYEE, MANAGER, login, requireRealStack } from './helpers';

import type { Page } from '@playwright/test';

// Compensatory Time: needs approval and has no accrual balance, so any future year works.
// Picks a random business day 30-330 days out (asks the API, so holidays are skipped);
// reruns on the same DB rarely collide and each run cancels its own request.
async function futureBusinessDay(page: Page): Promise<string> {
  return page.evaluate(async () => {
    const token = window.localStorage.getItem('hrms.token');
    for (let attempt = 0; attempt < 20; attempt++) {
      const d = new Date();
      d.setDate(d.getDate() + 30 + Math.floor(Math.random() * 300));
      const day = d.toISOString().slice(0, 10);
      const res = await fetch(`/api/v1/leave/business-days?startDate=${day}&endDate=${day}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if ((await res.json()).businessDays === 1) return day;
    }
    throw new Error('no business day found');
  });
}

test.describe('leave', () => {
  requireRealStack();

  test('employee submits leave, manager approves, employee cancels', async ({ browser }) => {
    const employee = await browser.newPage();
    await login(employee, EMPLOYEE);
    const day = await futureBusinessDay(employee);
    await employee.getByRole('tab', { name: 'Request leave' }).click();
    await employee.getByLabel('Leave type').selectOption({ label: 'Compensatory Time' });
    await employee.getByLabel('Start date').fill(day);
    await employee.getByLabel('End date').fill(day);
    await employee.getByLabel('Reason').fill('e2e');
    await employee.getByRole('button', { name: 'Submit request' }).click();
    const status = employee.getByRole('status');
    await expect(status).toContainText('(PENDING, 1 day(s))');
    const requestId = (await status.textContent())?.match(/#(\d+)/)?.[1];
    expect(requestId).toBeTruthy();

    const manager = await browser.newPage();
    await login(manager, MANAGER);
    await manager.getByRole('tab', { name: 'Approvals' }).click();
    const row = manager.getByRole('row').filter({ has: manager.getByRole('cell', { name: requestId!, exact: true }) });
    await row.getByRole('button', { name: 'Approve' }).click();
    await expect(row).toHaveCount(0);

    await employee.reload();
    const mine = employee.getByRole('row').filter({ has: employee.getByRole('cell', { name: requestId!, exact: true }) });
    await expect(mine).toContainText('APPROVED');
    employee.once('dialog', (d) => void d.accept('e2e cleanup'));
    await mine.getByRole('button', { name: 'Cancel' }).click();
    await expect(mine).toContainText('CANCELLED');
  });
});
