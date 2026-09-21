import { useParams } from 'react-router-dom';
import { MODULE_TILES } from '../app/modules';

/**
 * Route target for a module whose proxy flag is already `NEW*` but whose React page is
 * delivered by a later phase. Only reachable when `VITE_MODULE_FLAGS` promotes the module.
 */
export function ModulePlaceholderPage() {
  const { moduleId } = useParams();
  const tile = MODULE_TILES.find((t) => t.id === moduleId);
  return (
    <section className="module-placeholder">
      <h1>{tile?.label ?? 'Module'}</h1>
      <p>This module has been promoted to the new platform; its pages arrive in a later phase.</p>
    </section>
  );
}
