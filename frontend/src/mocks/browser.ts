import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers);

export async function startBrowserMocks() {
  await worker.start({ onUnhandledRequest: 'bypass', quiet: true });
}
