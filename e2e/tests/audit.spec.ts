import { expect, test } from '@playwright/test';
import { EMPLOYEE, login, requireRealStack } from './helpers';

const ADMIN = 'james.richardson@company.com'; // emp 1, grade 10

test.describe('audit', () => {
  requireRealStack();

  test('admin searches the audit log, opens an entry and its record trail', async ({ page }) => {
    await login(page, ADMIN);
    // The purge writes an AUDIT_LOG entry, so the search below always has a known row.
    await page.getByRole('link', { name: 'Audit' }).click();
    await expect(page.getByRole('heading', { name: 'Audit Log' })).toBeVisible();
    await page.getByRole('tab', { name: 'Purge' }).click();
    page.once('dialog', (dialog) => void dialog.accept());
    await page.getByRole('button', { name: 'Purge old entries' }).click();
    await expect(page.getByRole('status')).toContainText(/Purged \d+ audit entr/);

    await page.getByRole('tab', { name: 'Search' }).click();
    await page.getByLabel('Table').fill('audit_log');
    await page.getByLabel('Action').selectOption('DELETE');
    await page.getByLabel('Changed by').fill(ADMIN);
    await page.getByRole('button', { name: 'Search' }).click();
    const entries = page.getByRole('table', { name: 'Audit entries' });
    await expect(entries.getByRole('row').nth(1)).toContainText('AUDIT_LOG');
    await entries.getByRole('button', { name: 'Details' }).first().click();
    await expect(page.getByLabel('New values')).toContainText('"retentionDays"');

    await page.getByRole('tab', { name: 'Record trail' }).click();
    await page.getByLabel('Table name').fill('AUDIT_LOG');
    await page.getByLabel('Record number').fill('0');
    await page.getByRole('button', { name: 'Show trail' }).click();
    await expect(page.getByRole('table', { name: 'Record trail' }).getByRole('row').nth(1)).toContainText('DELETE');
  });

  test('plain employee has no Audit navigation and is denied by the API', async ({ page }) => {
    await login(page, EMPLOYEE);
    await expect(page.getByRole('link', { name: 'Audit' })).toHaveCount(0);
    await page.goto('/audit');
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByRole('alert')).toContainText('-20304');
  });
});
