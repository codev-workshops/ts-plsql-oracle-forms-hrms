import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useMemo, type ReactNode } from 'react';
import { BrowserRouter, MemoryRouter, Navigate, Route, Routes } from 'react-router-dom';
import type { CurrentUser } from './api/types';
import { AppShell, MODULE_FLAGS } from './app/AppShell';
import { AuthProvider } from './app/AuthContext';
import { ErrorBoundary } from './app/ErrorBoundary';
import { MODULE_TILES, isModulePromoted } from './app/modules';
import { ProtectedRoute } from './app/ProtectedRoute';
import { ToastProvider } from './app/ToastContext';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { ForbiddenPage } from './pages/ForbiddenPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { ModulePlaceholderPage } from './pages/ModulePlaceholderPage';

export function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } });
}

export function AppRoutes() {
  const promoted = MODULE_TILES.filter((t) => isModulePromoted(t, MODULE_FLAGS));
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute allowMustChangePassword />}>
        <Route element={<AppShell />}>
          <Route path="/password" element={<ChangePasswordPage />} />
        </Route>
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          <Route index element={<HomePage />} />
          <Route path="/forbidden" element={<ForbiddenPage />} />
          {promoted.map((t) => (
            <Route key={t.id} element={<ProtectedRoute anyOf={t.anyOf} />}>
              <Route path={`${t.path}/*`} element={<ModulePlaceholderPage />} />
            </Route>
          ))}
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export interface AppProvidersProps {
  children: ReactNode;
  queryClient?: QueryClient;
  initialUser?: CurrentUser | null;
  /** Test-only: render with a MemoryRouter at these entries instead of BrowserRouter. */
  initialEntries?: string[];
}

export function AppProviders({ children, queryClient, initialUser = null, initialEntries }: AppProvidersProps) {
  const qc = useMemo(() => queryClient ?? createQueryClient(), [queryClient]);
  const Router = initialEntries ? MemoryRouter : BrowserRouter;
  return (
    <ErrorBoundary>
      <QueryClientProvider client={qc}>
        <ToastProvider>
          <AuthProvider initialUser={initialUser}>
            <Router {...(initialEntries ? { initialEntries } : {})}>{children}</Router>
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}

export default function App() {
  return (
    <AppProviders>
      <AppRoutes />
    </AppProviders>
  );
}
