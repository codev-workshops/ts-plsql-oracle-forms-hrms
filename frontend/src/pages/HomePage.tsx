import { Link } from 'react-router-dom';
import { MODULE_FLAGS } from '../app/AppShell';
import { useAuth } from '../app/AuthContext';
import { visibleTiles } from '../app/modules';

/** Replaces HRMS_MENU `WELCOME_TEXT` + the six module buttons (COMPONENT_MAPPING.md §2). */
export function HomePage() {
  const { user } = useAuth();
  if (!user) return null;
  const tiles = visibleTiles(user.roles, MODULE_FLAGS);

  return (
    <section className="home-page">
      <h1>Welcome, {user.displayName}</h1>
      <p className="user-meta">
        {user.empNumber}
        {user.jobTitle ? ` · ${user.jobTitle}` : ''}
      </p>
      <div className="tiles" role="list">
        {tiles.map((t) =>
          t.promoted ? (
            <Link key={t.id} to={t.path} className="tile" role="listitem" data-testid={`tile-${t.id}`}>
              <h2>{t.label}</h2>
              <p>{t.description}</p>
            </Link>
          ) : (
            // Legacy module: full navigation so the reverse proxy performs the SSO exchange
            // and opens the Forms module; the SPA never touches /legacy/sso/exchange itself.
            <a key={t.id} href={t.path} className="tile tile-legacy" role="listitem" data-testid={`tile-${t.id}`} data-legacy="true">
              <h2>{t.label}</h2>
              <p>{t.description}</p>
              <small>Opens in Oracle Forms</small>
            </a>
          ),
        )}
      </div>
    </section>
  );
}
