import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, call, getToken, onSessionExpired, setToken, type Schemas } from '../api/client';

export type CurrentUser = Schemas['CurrentUser'];
export type Menu = Schemas['Menu'];
export type Module = Schemas['Module'];
export type Action = Schemas['Action'];

interface AuthState {
  user: CurrentUser | null;
  menu: Menu | null;
  loading: boolean;
  notice: string | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Usability only: the server enforces every permission (D-06). */
  has: (module: Module, action: Action) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [menu, setMenu] = useState<Menu | null>(null);
  const [loading, setLoading] = useState<boolean>(() => getToken() !== null);
  const [notice, setNotice] = useState<string | null>(null);

  const clear = useCallback((message: string | null) => {
    setToken(null);
    setUser(null);
    setMenu(null);
    setNotice(message);
  }, []);

  const loadSession = useCallback(async () => {
    const [me, m] = await Promise.all([call(api.GET('/api/v1/auth/me')), call(api.GET('/api/v1/menu'))]);
    setUser(me);
    setMenu(m);
  }, []);

  useEffect(() => {
    onSessionExpired(() => clear('Your session has expired. Please sign in again.'));
    return () => onSessionExpired(null);
  }, [clear]);

  useEffect(() => {
    if (!getToken()) return;
    loadSession()
      .catch(() => clear(null))
      .finally(() => setLoading(false));
  }, [loadSession, clear]);

  const login = useCallback(
    async (username: string, password: string) => {
      const res = await call(api.POST('/api/v1/auth/login', { body: { username, password } }));
      setToken(res.accessToken);
      setNotice(null);
      await loadSession();
    },
    [loadSession],
  );

  const logout = useCallback(async () => {
    try {
      await call(api.POST('/api/v1/auth/logout'));
    } finally {
      clear('You have signed out.');
    }
  }, [clear]);

  const has = useCallback(
    (module: Module, action: Action) => Boolean(user?.permissions?.[module]?.includes(action)),
    [user],
  );

  const value = useMemo(
    () => ({ user, menu, loading, notice, login, logout, has }),
    [user, menu, loading, notice, login, logout, has],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
