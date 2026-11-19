import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { MODULES } from './modules';

export function Layout() {
  const { user, logout } = useAuth();
  const grade = user?.gradeLevel ?? 0;
  return (
    <div className="shell">
      <header className="topbar">
        <strong>HRMS</strong>
        <nav aria-label="Main">
          {MODULES.filter((m) => grade >= m.minGrade).map((m) => (
            <NavLink key={m.path} to={m.path}>
              {m.label}
            </NavLink>
          ))}
        </nav>
        <span className="user">{user?.fullName}</span>
        <button type="button" onClick={() => void logout()}>
          Sign out
        </button>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
