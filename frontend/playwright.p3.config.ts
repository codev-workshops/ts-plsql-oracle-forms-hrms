import { defineConfig } from '@playwright/test';
// integration-session harness: real stack, two stages in one run
export default defineConfig({
  testDir: './e2e',
  testMatch: /p3-real-stack\.spec\.ts/,
  timeout: 60_000,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-p3' }]],
  use: { trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'retain-on-failure' },
  projects: [
    { name: 'stage1-NEW_READONLY', use: { baseURL: 'http://localhost:5173' }, metadata: {} },
    { name: 'stage2-NEW', use: { baseURL: 'http://localhost:5174' }, dependencies: ['stage1-NEW_READONLY'] },
  ],
  webServer: [
    { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true, env: { VITE_MOCK_API: 'false', VITE_MODULE_FLAGS: 'employee=NEW_READONLY,leave=NEW,performance=NEW' } },
    { command: 'npx vite --config vite.p3new.config.ts', url: 'http://localhost:5174', reuseExistingServer: true, env: { VITE_MOCK_API: 'false', VITE_MODULE_FLAGS: 'employee=NEW,leave=NEW,performance=NEW' } },
  ],
});
