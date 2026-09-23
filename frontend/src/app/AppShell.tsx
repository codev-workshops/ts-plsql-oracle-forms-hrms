import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { useErrorHandler } from './useErrorHandler';
import { useModuleFlags } from './ModuleFlagsContext';
import { MODULE_FLAGS, isDecommissioned, visibleTiles } from './modules';

export { MODULE_FLAGS };

/**
 * Replaces HRMS_MENU (`MM_HRMS` top menu + `USER_INFO`) – COMPONENT_MAPPING.md §2.
 * Non-promoted modules render as legacy (`data-legacy`) links – the SSO-bridge entry points –
 * until `decommission=NEW` with every module NEW removes them (CUTOVER_PLAN.md §9.3).
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

  const flags = useModuleFlags();
  const tiles = user ? visibleTiles(user.roles, flags) : [];
  const decommissioned = isDecommissioned(flags);

  return (
    <div className="app-shell">
      <header className="app-header" data-decommissioned={decommissioned ? 'true' : undefined}>
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
