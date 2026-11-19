import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import { EMPLOYEE, MANAGER, login, requireRealStack } from './helpers';

test.describe('reporting', () => {
  requireRealStack();

  test('filters headcount via the shared location ID and downloads the displayed report', async ({ page }) => {
    await login(page, MANAGER);
    await page.getByRole('link', { name: 'Reports' }).click();
    await page.getByLabel('Location ID').fill('2');
    await page.getByRole('button', { name: 'Run report' }).click();

    const table = page.getByRole('table', { name: 'Headcount' });
    await expect(table.getByRole('cell', { name: 'Corporate Headquarters' }).first()).toBeVisible();
    await expect(table.getByRole('cell', { name: 'Chicago Regional Office' })).toHaveCount(0);
    await expect(table.getByRole('cell', { name: 'San Francisco Branch' })).toHaveCount(0);

    // Unsubmitted edits shouldn't change the query of the displayed export.
    await page.getByLabel('Location ID').fill('1');
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: 'Download CSV' }).click(),
    ]);
    expect(download.suggestedFilename()).toBe('headcount.csv');
    const csv = await readFile(await download.path(), 'utf8');
    expect(csv.split('\r\n')[0]).toContain('location_id,location_name');
    expect(csv).toContain('Corporate Headquarters');
    expect(csv).not.toContain('Chicago Regional Office');
    expect(csv).not.toContain('San Francisco Branch');
  });

  test('keeps Reports hidden and blocks direct navigation for grade 4', async ({ page }) => {
    await login(page, EMPLOYEE);
    await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Reports' })).toHaveCount(0);
    await page.goto('/reports');
    await expect(page.getByRole('alert')).toHaveText('You do not have permission to view reports.');
  });
});
