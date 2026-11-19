import { expect, test } from '@playwright/test';
import { EMPLOYEE, MANAGER, login, requireRealStack } from './helpers';

import type { Page } from '@playwright/test';

const HR_ADMIN = 'james.richardson@company.com'; // emp 1, grade 10

async function openPerformance(page: Page, username: string) {
  await login(page, username);
  await page.goto('/performance');
  await expect(page.getByRole('heading', { name: 'Performance' })).toBeVisible();
}

test.describe('performance', () => {
  requireRealStack();

  test('HR opens a cycle, employee self-assesses, manager rates, employee acknowledges', async ({ browser }) => {
    // Each run creates its own cycle, so reruns on the same DB never collide.
    const cycleName = `E2E cycle ${Date.now()}`;
    const admin = await browser.newPage();
    await openPerformance(admin, HR_ADMIN);
    await admin.getByRole('tab', { name: 'Review cycles' }).click();
    await admin.getByLabel('Cycle name').fill(cycleName);
    await admin.getByLabel('Start date').fill('2027-01-01');
    await admin.getByLabel('End date').fill('2027-12-31');
    await admin.getByRole('button', { name: 'Create cycle' }).click();
    await expect(admin.getByRole('status')).toContainText('created (DRAFT)');
    const cycleId = (await admin.getByRole('status').textContent())?.match(/#(\d+)/)?.[1];
    expect(cycleId).toBeTruthy();
    const cycleRow = admin.getByRole('row').filter({ has: admin.getByRole('cell', { name: cycleName, exact: true }) });
    await cycleRow.getByRole('button', { name: 'Open' }).click();
    await expect(cycleRow).toContainText('OPEN');
    await cycleRow.getByRole('button', { name: 'Generate reviews' }).click();
    await expect(admin.getByRole('status')).toContainText(/[1-9]\d* review\(s\) generated/);
    await cycleRow.getByRole('button', { name: 'Generate reviews' }).click();
    await expect(admin.getByRole('status')).toContainText('0 review(s) generated');

    const employee = await browser.newPage();
    await openPerformance(employee, EMPLOYEE);
    const myRow = employee.getByRole('row').filter({ has: employee.getByRole('cell', { name: cycleId!, exact: true }) });
    await myRow.getByRole('button', { name: 'Open' }).click();
    await expect(employee.getByTestId('review-status')).toHaveText('NOT_STARTED');
    await employee.getByLabel('Goal title').fill('Automate month-end close');
    await employee.getByLabel('Weight %').fill('40');
    await employee.getByRole('button', { name: 'Add goal' }).click();
    await expect(employee.getByRole('status')).toHaveText('Goal added');
    await employee.getByLabel('Progress for Automate month-end close').fill('100');
    await employee.getByRole('button', { name: 'Update' }).click();
    const goalRow = employee.getByRole('row').filter({ hasText: 'Automate month-end close' });
    await expect(goalRow).toContainText('COMPLETED');
    await employee.getByLabel('Self assessment').fill('Closed the books two days early every month.');
    await employee.getByLabel('Self rating (1-5)').fill('4');
    await employee.getByRole('button', { name: 'Submit self-assessment' }).click();
    await expect(employee.getByTestId('review-status')).toHaveText('MANAGER_REVIEW');

    const manager = await browser.newPage();
    await openPerformance(manager, MANAGER);
    await manager.getByRole('tab', { name: 'Team reviews' }).click();
    const teamRow = manager.getByRole('row')
      .filter({ has: manager.getByRole('cell', { name: cycleId!, exact: true }) })
      .filter({ hasText: 'THOMAS BAKER' });
    await teamRow.getByRole('button', { name: 'Open' }).click();
    await manager.getByLabel('Overall rating (1-5)').fill('7');
    await manager.getByRole('button', { name: 'Submit manager review' }).click();
    await expect(manager.getByRole('alert')).toContainText('(-20403)');
    await manager.getByLabel('Overall rating (1-5)').fill('4.6');
    await manager.getByLabel('Manager comments').fill('Outstanding year.');
    await manager.getByRole('button', { name: 'Submit manager review' }).click();
    await expect(manager.getByTestId('review-status')).toHaveText('COMPLETED');
    await expect(manager.getByText('4.6 (Exceptional)')).toBeVisible();

    await employee.reload();
    await myRow.getByRole('button', { name: 'Open' }).click();
    await employee.getByRole('button', { name: 'Acknowledge review' }).click();
    await expect(employee.getByTestId('review-status')).toHaveText('ACKNOWLEDGED');

    await cycleRow.getByRole('button', { name: 'Ratings' }).click();
    await expect(admin.getByRole('table', { name: 'Rating distribution' })).toContainText('Exceptional');
    await cycleRow.getByRole('button', { name: 'Close' }).click();
    await expect(cycleRow).toContainText('CLOSED');
  });
});
