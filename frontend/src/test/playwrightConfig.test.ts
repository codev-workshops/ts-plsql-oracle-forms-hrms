// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';

async function loadPlaywrightConfig(env: Record<string, string>) {
  vi.resetModules();
  for (const [name, value] of Object.entries(env)) vi.stubEnv(name, value);
  return (await import('../../playwright.config')).default;
}

describe('playwright.config worker isolation', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('runs real-stack specs on a single worker because they share mutable seeded accounts', async () => {
    const config = await loadPlaywrightConfig({ E2E_REAL_STACK: '1' });
    expect(config.workers).toBe(1);
    expect(config.fullyParallel).not.toBe(true);
  });

  it('keeps mock-stack specs on the default parallel worker pool', async () => {
    const config = await loadPlaywrightConfig({ E2E_REAL_STACK: '0' });
    expect(config.workers).toBeUndefined();
  });
});
