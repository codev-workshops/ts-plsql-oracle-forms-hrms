import { defineConfig } from '@playwright/test';

/**
 * Two modes, selected by env (TEST_STRATEGY.md §7):
 *
 *  - default (mock): Vite serves the app with the msw browser worker (`VITE_MOCK_API=true`),
 *    no backend required. Level-1 smoke.
 *  - `E2E_REAL_STACK=1`: Level-3 real-stack run. Vite is started WITHOUT the mock worker and
 *    proxies `/api` to the running auth-service (vite.config.ts). The integration session
 *    starts PostgreSQL + auth-service (backend/auth docker compose) and seeds the accounts
 *    used by e2e/golden-path.spec.ts before invoking `E2E_REAL_STACK=1 npm run e2e`.
 *    Set `E2E_BASE_URL` to point at an already-running frontend/proxy (e.g. the nginx
 *    edge on http://localhost:8000) instead of letting Playwright start Vite.
 *
 * P0-D1: legacy-tile navigation into Oracle Forms is skipped in both modes.
 */
const realStack = process.env.E2E_REAL_STACK === '1';
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';
const startViteServer = !process.env.E2E_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: { baseURL, trace: 'retain-on-failure' },
  webServer: startViteServer
    ? {
        command: 'npm run dev',
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        env: {
          VITE_MOCK_API: realStack ? 'false' : 'true',
          VITE_MODULE_FLAGS: process.env.VITE_MODULE_FLAGS ?? '',
        },
      }
    : undefined,
});
