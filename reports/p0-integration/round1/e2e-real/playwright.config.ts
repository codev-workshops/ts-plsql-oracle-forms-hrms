import { defineConfig } from '@playwright/test';

// Real stack: nginx proxy (:8888) -> Spring auth-service (:8080) / Vite app shell (:3000) -> PostgreSQL (:5432)
export default defineConfig({
  testDir: './tests',
  timeout: 45_000,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { outputFolder: 'report', open: 'never' }], ['json', { outputFile: 'results.json' }]],
  use: {
    baseURL: process.env.HRMS_BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ignoreHTTPSErrors: true,
  },
});
