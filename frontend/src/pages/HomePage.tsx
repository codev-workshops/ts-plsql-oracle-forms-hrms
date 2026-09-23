import { Link } from 'react-router-dom';
import { useAuth } from '../app/AuthContext';
import { useModuleFlags } from '../app/ModuleFlagsContext';
import { visibleTiles } from '../app/modules';

/** Replaces HRMS_MENU `WELCOME_TEXT` + the six module buttons (COMPONENT_MAPPING.md §2). */
export function HomePage() {
  const { user } = useAuth();
  const flags = useModuleFlags();
  if (!user) return null;
  const tiles = visibleTiles(user.roles, flags);

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
            // Legacy module (P0-D1, golden-oracle mode OFF): Oracle Forms are not run in this
            // environment, so the tile is rendered disabled and never navigates.
            <div
              key={t.id}
              className="tile tile-legacy tile-disabled"
              role="listitem"
              aria-disabled="true"
              data-testid={`tile-${t.id}`}
              data-legacy="true"
            >
              <h2>{t.label}</h2>
              <p>{t.description}</p>
              <small>Not available in this environment</small>
            </div>
          ),
        )}
      </div>
    </section>
  );
}
