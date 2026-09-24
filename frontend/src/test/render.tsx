import { render, type RenderOptions } from '@testing-library/react';
import type { ReactElement } from 'react';
import { AppProviders, createQueryClient } from '../App';
import type { CurrentUser } from '../api/types';
import type { ModuleFlagValue } from '../app/modules';

export function renderWithProviders(
  ui: ReactElement,
  opts: { initialUser?: CurrentUser | null; initialEntries?: string[]; moduleFlags?: Record<string, ModuleFlagValue> } & Omit<RenderOptions, 'wrapper'> = {},
) {
  const { initialUser = null, initialEntries = ['/'], moduleFlags, ...rest } = opts;
  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({ queries: { retry: false } });
  return render(ui, {
    wrapper: ({ children }) => (
      <AppProviders queryClient={queryClient} initialUser={initialUser} initialEntries={initialEntries} moduleFlags={moduleFlags}>
        {children}
      </AppProviders>
    ),
    ...rest,
  });
}
