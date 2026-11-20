import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** HRMS_MENU equivalent: rendered from GET /api/v1/menu; disabled entries are shown but not linked. */
export function Layout() {
  const { user, menu, logout } = useAuth();
  return (
    <div className="shell">
      <nav aria-label="Main menu">
        <strong>HRMS</strong>
        <ul>
          {menu?.modules.map((m) => (
            <li key={m.id}>
              {m.enabled && m.route ? (
                <NavLink to={m.route}>{m.label}</NavLink>
              ) : (
                <span className="disabled" aria-disabled="true" title="Requires permission">
                  {m.label}
                </span>
              )}
            </li>
          ))}
        </ul>
        <ul aria-label="Account">
          {menu?.account.map((a) =>
            a.id === 'LOGOUT' ? (
              <li key={a.id}>
                <button type="button" onClick={() => void logout()}>
                  {a.label}
                </button>
              </li>
            ) : (
              <li key={a.id}>{a.route && <NavLink to={a.route}>{a.label}</NavLink>}</li>
            ),
          )}
        </ul>
        <p className="muted">
          {user?.firstName} {user?.lastName} ({user?.empNumber})
        </p>
      </nav>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
