import { expect, test, type Page } from '@playwright/test';

export const PASSWORD = process.env.E2E_PASSWORD ?? 'Hrms#2026';
export const EMPLOYEE = 'thomas.baker@company.com'; // emp 22, reports to 21
export const MANAGER = 'jennifer.park@company.com'; // emp 21

export function requireRealStack() {
  test.skip(process.env.E2E_REAL_STACK !== '1', 'set E2E_REAL_STACK=1 to run against the real stack');
}

export async function login(page: Page, username: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(username);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Leave' })).toBeVisible();
}
