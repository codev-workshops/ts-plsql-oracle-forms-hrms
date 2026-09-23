import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api/client';
import { bindSessionHandlers, refreshAccessToken, setAccessToken } from '../api/http';
import type { Authority, CurrentUser, LoginRequest, TokenResponse } from '../api/types';

/**
 * Replaces `:GLOBAL.session_id / current_user / current_emp_id` (COMPONENT_MAPPING.md §1, §7).
 * Identity is whatever the server derived from the JWT; the token itself is held in memory
 * by `api/http.ts` and never persisted. On mount we try one silent refresh (HttpOnly cookie)
 * to restore a session after a reload. The refresh goes through the single-flight
 * `refreshAccessToken` so a StrictMode double effect (or a concurrent 401 replay) never sends
 * the same rotating `hrms_refresh` token twice — the server treats that as a replay.
 */

export type AuthStatus = 'initialising' | 'anonymous' | 'authenticated';

export interface AuthState {
  status: AuthStatus;
  user: CurrentUser | null;
  /** Message to show on /login after an involuntary logout (session expiry). */
  sessionMessage: string | null;
  login: (req: LoginRequest) => Promise<TokenResponse>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
  hasAuthority: (authority: Authority) => boolean;
  hasAnyAuthority: (...authorities: Authority[]) => boolean;
  clearSessionMessage: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export const SESSION_EXPIRED_MESSAGE = 'Session has expired';

export function AuthProvider({ children, initialUser = null }: { children: ReactNode; initialUser?: CurrentUser | null }) {
  const [status, setStatus] = useState<AuthStatus>(initialUser ? 'authenticated' : 'initialising');
  const [user, setUser] = useState<CurrentUser | null>(initialUser);
  const [sessionMessage, setSessionMessage] = useState<string | null>(null);

  useEffect(() => {
    bindSessionHandlers({
      onTokenRefreshed: (t) => setUser(t.user),
      onSessionExpired: () => {
        setUser(null);
        setStatus('anonymous');
        setSessionMessage(SESSION_EXPIRED_MESSAGE);
      },
    });
  }, []);

  useEffect(() => {
    if (status !== 'initialising') return;
    let cancelled = false;
    refreshAccessToken()
      .then((t) => {
        if (cancelled) return;
        setUser(t.user);
        setStatus('authenticated');
      })
      .catch(() => {
        if (cancelled) return;
        setAccessToken(null);
        setStatus('anonymous');
      });
    return () => {
      cancelled = true;
    };
  }, [status]);

  const login = useCallback(async (req: LoginRequest) => {
    const t = await api.auth.login(req);
    setUser(t.user);
    setStatus('authenticated');
    setSessionMessage(null);
    return t;
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.auth.logout();
    } finally {
      setUser(null);
      setStatus('anonymous');
    }
  }, []);

  const refreshUser = useCallback(async () => {
    const me = await api.auth.me();
    setUser(me);
  }, []);

  const hasAuthority = useCallback((a: Authority) => user?.roles.includes(a) ?? false, [user]);
  const hasAnyAuthority = useCallback((...as: Authority[]) => as.some((a) => user?.roles.includes(a)), [user]);
  const clearSessionMessage = useCallback(() => setSessionMessage(null), []);

  const value = useMemo<AuthState>(
    () => ({ status, user, sessionMessage, login, logout, refreshUser, hasAuthority, hasAnyAuthority, clearSessionMessage }),
    [status, user, sessionMessage, login, logout, refreshUser, hasAuthority, hasAnyAuthority, clearSessionMessage],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
