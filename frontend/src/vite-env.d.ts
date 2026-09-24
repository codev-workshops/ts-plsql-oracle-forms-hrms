/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** `module=FLAG,...` using the frozen proxy-flag vocabulary (contracts/p0-foundation/README.md). */
  readonly VITE_MODULE_FLAGS?: string;
  /** `true` → start the msw browser worker (Playwright smoke run / local dev without a backend). */
  readonly VITE_MOCK_API?: string;
}
