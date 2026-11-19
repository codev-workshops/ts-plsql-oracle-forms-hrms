import { defineConfig } from '@playwright/test';

// Real-stack e2e: tests are skipped unless E2E_REAL_STACK=1. Expects the stack from
// docs/modernization/slice-recipe.md §Run (proxy on :8000 -> backend :8080 + frontend :5173).
export default defineConfig({
  testDir: './tests',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 30_000,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8000',
    trace: 'retain-on-failure',
  },
});
