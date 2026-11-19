import { expect, test } from '@playwright/test';
import { EMPLOYEE, MANAGER, login, requireRealStack } from './helpers';

// Picks a weekday about 300-360 days out so reruns on the same DB rarely overlap; each run cancels its request.
function futureWeekday(): string {
  const d = new Date();
  d.setDate(d.getDate() + 300 + Math.floor(Math.random() * 60));
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10);
}

test.describe('leave', () => {
  requireRealStack();

  test('employee submits sick leave, manager approves, employee cancels', async ({ browser }) => {
    const day = futureWeekday();
    const employee = await browser.newPage();
    await login(employee, EMPLOYEE);
    await employee.getByRole('tab', { name: 'Request leave' }).click();
    await employee.getByLabel('Leave type').selectOption({ label: 'Sick Leave' });
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
