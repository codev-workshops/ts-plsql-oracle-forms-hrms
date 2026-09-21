import { defineConfig } from '@playwright/test';

/**
 * Smoke-only configuration: the app is served by Vite with the msw browser worker
 * (`VITE_MOCK_API=true`) so no backend is required. The real end-to-end run against
 * auth-service is the integration session's job (TEST_STRATEGY.md §7).
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: { baseURL: 'http://localhost:5173', trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    env: { VITE_MOCK_API: 'true' },
  },
});
