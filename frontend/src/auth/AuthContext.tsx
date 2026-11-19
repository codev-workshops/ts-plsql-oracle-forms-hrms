import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, onUnauthorized, tokenStore } from '../api/client';

export interface CurrentUser {
  empId: number;
  username: string;
  fullName: string;
  gradeLevel: number;
  managerEmpId?: number | null;
  sessionId: number;
}

interface AuthState {
  user: CurrentUser | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState<boolean>(() => tokenStore.get() !== null);

  useEffect(() => {
    onUnauthorized(() => {
      tokenStore.clear();
      setUser(null);
    });
    if (tokenStore.get()) {
      api<CurrentUser>('/auth/me')
        .then(setUser)
        .catch(() => tokenStore.clear())
        .finally(() => setLoading(false));
    }
    return () => onUnauthorized(null);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const result = await api<{ token: string; user: CurrentUser }>('/auth/login', {
      method: 'POST',
      body: { username, password },
    });
    tokenStore.set(result.token);
    setUser(result.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await api<void>('/auth/logout', { method: 'POST' });
    } finally {
      tokenStore.clear();
      setUser(null);
    }
  }, []);

  const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
