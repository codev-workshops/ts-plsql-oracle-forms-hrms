import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { setToken } from '../api/client';

afterEach(() => {
  cleanup();
  setToken(null);
  vi.unstubAllGlobals();
});
