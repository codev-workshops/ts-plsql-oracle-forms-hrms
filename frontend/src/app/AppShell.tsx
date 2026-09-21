import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { useErrorHandler } from './useErrorHandler';
import { visibleTiles, parseModuleFlags } from './modules';

export const MODULE_FLAGS = parseModuleFlags(import.meta.env.VITE_MODULE_FLAGS);

/**
 * Replaces HRMS_MENU (`MM_HRMS` top menu + `USER_INFO`) – COMPONENT_MAPPING.md §2.
 */
export function AppShell() {
  const { user, logout } = useAuth();
  const { handleError } = useErrorHandler();
  const navigate = useNavigate();

  const onLogout = async () => {
    try {
      await logout();
    } catch (e) {
      handleError(e);
    } finally {
      navigate('/login', { replace: true });
    }
  };

  const tiles = user ? visibleTiles(user.roles, MODULE_FLAGS) : [];

  return (
    <div className="app-shell">
      <header className="app-header">
        <Link to="/" className="brand">
          HRMS
        </Link>
        <nav aria-label="Modules" className="app-nav">
          {tiles.map((t) =>
            t.promoted ? (
              <NavLink key={t.id} to={t.path}>
                {t.label}
              </NavLink>
            ) : (
              <a key={t.id} href={t.path} data-legacy="true">
                {t.label}
              </a>
            ),
          )}
        </nav>
        <div className="user-info" data-testid="user-info">
          <span>{user?.displayName}</span>
          <Link to="/password">Change password</Link>
          <button type="button" onClick={onLogout}>
            Logout
          </button>
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
