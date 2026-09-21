import { render, type RenderOptions } from '@testing-library/react';
import type { ReactElement } from 'react';
import { AppProviders, createQueryClient } from '../App';
import type { CurrentUser } from '../api/types';

export function renderWithProviders(
  ui: ReactElement,
  opts: { initialUser?: CurrentUser | null; initialEntries?: string[] } & Omit<RenderOptions, 'wrapper'> = {},
) {
  const { initialUser = null, initialEntries = ['/'], ...rest } = opts;
  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({ queries: { retry: false } });
  return render(ui, {
    wrapper: ({ children }) => (
      <AppProviders queryClient={queryClient} initialUser={initialUser} initialEntries={initialEntries}>
        {children}
      </AppProviders>
    ),
    ...rest,
  });
}
